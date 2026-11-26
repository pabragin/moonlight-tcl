package com.limelight.binding.audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

public class AndroidAudioRenderer implements AudioRenderer {

    private static final int DOLBY_MAX_PENDING_MS = 64;
    private static final long UNDERRUN_REFRESH_MS = 1000;
    private static final long REOPEN_COOLDOWN_MS = 1000;

    interface DirectPlaybackSupportQuery {
        int query(AudioFormat format, AudioAttributes attributes);
    }

    static DirectPlaybackSupportQuery directPlaybackQuery = AudioManager::getDirectPlaybackSupport;

    private static volatile boolean active;
    private static volatile String activeFormatLabel = "AudioTrack";
    private static volatile int lastPendingMs;
    private static volatile int lastUnderruns;
    private static volatile int lastDroppedPackets;

    private final Context context;
    private final boolean enableAudioFx;
    private final int maxPendingMs;

    private AudioTrack track;
    private int channelCount;
    private int sampleRate;
    private int activeEncoding;
    private int encodedBufferSize;
    private int droppedPackets;
    private long lastDropLogUptime;
    private long lastUnderrunRefreshUptime;
    private long lastUnderrunLogUptime;
    private long lastReopenAttemptUptime;
    private int lastLoggedUnderruns;
    private int lastLoggedDropped;

    public AndroidAudioRenderer(Context context, boolean enableAudioFx) {
        this(context, enableAudioFx, 40);
    }

    // maxPendingMs: how much decoded audio may queue up behind a blocking write before packets are
    // dropped (upstream: 40 ms)
    public AndroidAudioRenderer(Context context, boolean enableAudioFx, int maxPendingMs) {
        this.context = context;
        this.enableAudioFx = enableAudioFx;
        this.maxPendingMs = maxPendingMs > 0 ? maxPendingMs : 40;
    }

    private AudioTrack createAudioTrack(int channelConfig, int sampleRate, int bufferSize, boolean lowLatency) {
        AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME);
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();

        AudioTrack.Builder trackBuilder = new AudioTrack.Builder()
                .setAudioFormat(format)
                .setAudioAttributes(attributesBuilder.build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize);

        // Use PERFORMANCE_MODE_LOW_LATENCY on O and later
        if (lowLatency) {
            trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        }

        return trackBuilder.build();
    }

    private static AudioAttributes buildEncodedAttributes(boolean gameUsage) {
        AudioAttributes.Builder attrBuilder = new AudioAttributes.Builder()
                .setUsage(gameUsage ? AudioAttributes.USAGE_GAME : AudioAttributes.USAGE_MEDIA);
        if (!gameUsage) {
            attrBuilder.setContentType(AudioAttributes.CONTENT_TYPE_MOVIE);
        }
        return attrBuilder.build();
    }

    /**
     * Probes Android direct playback support (API 33+) for AC-3 and E-AC-3 before RTSP negotiation.
     * Accepts both bitstream (eARC/AVR) and offload (internal TV Dolby decode) support.
     */
    static int probeSupportedDolbyCodecs(Context context, MoonBridge.AudioConfiguration audioConfig) {
        if (audioConfig == null) {
            return MoonBridge.AUDIO_CODEC_OPUS;
        }
        // Stereo stays on Opus: a 32 ms Dolby frame only adds latency there
        int channels = audioConfig.channelCount;
        if (channels != 6 && channels != 8) {
            return MoonBridge.AUDIO_CODEC_OPUS;
        }
        int channelMask = AudioFormat.CHANNEL_OUT_5POINT1;
        int[] encodings = {AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3};
        int[] codecFlags = {MoonBridge.AUDIO_CODEC_AC3, MoonBridge.AUDIO_CODEC_EAC3};
        AudioAttributes gameAttrs = buildEncodedAttributes(true);
        AudioAttributes mediaAttrs = buildEncodedAttributes(false);

        int supported = MoonBridge.AUDIO_CODEC_OPUS;
        for (int i = 0; i < encodings.length; i++) {
            try {
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(encodings[i])
                        .setSampleRate(48000)
                        .setChannelMask(channelMask)
                        .build();
                int support = directPlaybackQuery.query(format, gameAttrs);
                if (support == AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED) {
                    support = directPlaybackQuery.query(format, mediaAttrs);
                }
                if (support != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED) {
                    supported |= codecFlags[i];
                }
            } catch (Exception e) {
                LimeLog.warning("Dolby direct playback probe failed for encoding " + encodings[i] + ": " + e.getMessage());
            }
        }
        LimeLog.info("Dolby direct playback probe (mask 0x" + Integer.toHexString(channelMask)
                + "): supported=0x" + Integer.toHexString(supported));
        return supported;
    }

    AudioTrack createEncodedAudioTrack(int encoding, int channelMask, int trackSampleRate, int bufferSize, boolean gameUsage) {
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(trackSampleRate)
                .setChannelMask(channelMask)
                .build();

        return new AudioTrack.Builder()
                .setAudioAttributes(buildEncodedAttributes(gameUsage))
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                .build();
    }

    private void releaseTrack() {
        if (track != null) {
            try {
                track.pause();
                track.flush();
            } catch (Exception ignored) {
            }
            try {
                track.release();
            } catch (Exception ignored) {
            }
            track = null;
        }
    }

    private static String formatLabel(int encoding, int channels) {
        if (encoding == AudioFormat.ENCODING_E_AC3) {
            return channels >= 6 ? "E-AC-3 5.1" : "E-AC-3";
        }
        if (encoding == AudioFormat.ENCODING_AC3) {
            return channels >= 6 ? "AC-3 5.1" : "AC-3";
        }
        return "AudioTrack";
    }

    private boolean setupEncodedTrack(int encoding, int frameBytes) {
        releaseTrack();
        activeEncoding = 0;
        encodedBufferSize = 0;

        int rate = sampleRate > 0 ? sampleRate : 48000;
        int encodedMask = channelCount >= 6 ? AudioFormat.CHANNEL_OUT_5POINT1 : AudioFormat.CHANNEL_OUT_STEREO;
        int rawMin = AudioTrack.getMinBufferSize(rate, encodedMask, encoding);
        int minBuffer = rawMin > 0 ? rawMin : frameBytes;
        int oneFrameBuf = Math.max(minBuffer, frameBytes);
        int twoFrameBuf = Math.max(minBuffer, frameBytes * 2);
        int compatBuf = Math.max(minBuffer, Math.max(frameBytes * 4, 16384));

        boolean[] gameModes = {true, false, false, false};
        int[] bufferSizes = {oneFrameBuf, oneFrameBuf, twoFrameBuf, compatBuf};
        Exception lastEx = null;
        for (int i = 0; i < gameModes.length; i++) {
            boolean gameUsage = gameModes[i];
            int bufferSize = bufferSizes[i];
            boolean duplicate = false;
            for (int j = 0; j < i; j++) {
                if (gameModes[j] == gameUsage && bufferSizes[j] == bufferSize) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                continue;
            }
            AudioTrack candidate = null;
            try {
                candidate = createEncodedAudioTrack(encoding, encodedMask, rate, bufferSize, gameUsage);
                candidate.play();
                track = candidate;
                activeEncoding = encoding;
                encodedBufferSize = bufferSize;
                activeFormatLabel = formatLabel(encoding, channelCount);
                active = true;
                LimeLog.info("Audio output: " + activeFormatLabel + " passthrough (buf "
                        + bufferSize + " bytes, min " + rawMin + ", game=" + gameUsage + ")");
                return true;
            } catch (Exception ex) {
                lastEx = ex;
                if (candidate != null) {
                    try {
                        candidate.release();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        LimeLog.severe("Dolby AudioTrack passthrough setup failed for encoding " + encoding
                + ": " + (lastEx != null ? lastEx.getMessage() : "unknown"));
        active = false;
        return false;
    }

    void setupForEncoded(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate) {
        this.channelCount = audioConfiguration.channelCount;
        this.sampleRate = sampleRate;
        this.activeEncoding = 0;
        this.encodedBufferSize = 0;
        this.droppedPackets = 0;
        this.lastDropLogUptime = 0;
        this.lastUnderrunRefreshUptime = 0;
        this.lastUnderrunLogUptime = 0;
        this.lastReopenAttemptUptime = 0;
        this.lastLoggedUnderruns = 0;
        this.lastLoggedDropped = 0;
        lastPendingMs = 0;
        lastUnderruns = 0;
        lastDroppedPackets = 0;
        activeFormatLabel = "AudioTrack";
        active = false;
    }

    @Override
    public int setupEncoded(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int encoding) {
        setupForEncoded(audioConfiguration, sampleRate);
        int initialFrameBytes = encoding == AudioFormat.ENCODING_E_AC3 ? 1536 : 2560;
        return setupEncodedTrack(encoding, initialFrameBytes) ? 0 : -2;
    }

    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        setupForEncoded(audioConfiguration, sampleRate);
        int channelConfig;
        int bytesPerFrame;

        switch (audioConfiguration.channelCount)
        {
            case 2:
                channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                break;
            case 4:
                channelConfig = AudioFormat.CHANNEL_OUT_QUAD;
                break;
            case 6:
                channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
                break;
            case 8:
                // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND isn't available until Android 6.0,
                // yet the CHANNEL_OUT_SIDE_LEFT and CHANNEL_OUT_SIDE_RIGHT constants were added
                // in 5.0, so just hardcode the constant so we can work on Lollipop.
                channelConfig = 0x000018fc; // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                break;
            default:
                LimeLog.severe("Decoder returned unhandled channel count");
                return -1;
        }

        LimeLog.info("Audio channel config: "+String.format("0x%X", channelConfig));

        bytesPerFrame = audioConfiguration.channelCount * samplesPerFrame * 2;

        // We're not supposed to request less than the minimum
        // buffer size for our buffer, but it appears that we can
        // do this on many devices and it lowers audio latency.
        // We'll try the small buffer size first and if it fails,
        // use the recommended larger buffer size.

        for (int i = 0; i < 4; i++) {
            boolean lowLatency;
            int bufferSize;

            // We will try:
            // 1) Small buffer, low latency mode
            // 2) Large buffer, low latency mode
            // 3) Small buffer, standard mode
            // 4) Large buffer, standard mode

            switch (i) {
                case 0:
                case 1:
                    lowLatency = true;
                    break;
                case 2:
                case 3:
                    lowLatency = false;
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            switch (i) {
                case 0:
                case 2:
                    bufferSize = bytesPerFrame * 2;
                    break;

                case 1:
                case 3:
                    // Try the larger buffer size
                    bufferSize = Math.max(AudioTrack.getMinBufferSize(sampleRate,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT),
                            bytesPerFrame * 2);

                    // Round to next frame
                    bufferSize = (((bufferSize + (bytesPerFrame - 1)) / bytesPerFrame) * bytesPerFrame);
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            // Skip low latency options if hardware sample rate doesn't match the content
            if (AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) != sampleRate && lowLatency) {
                continue;
            }

            // Skip low latency options when using audio effects, since low latency mode
            // precludes the use of the audio effect pipeline (as of Android 13).
            if (enableAudioFx && lowLatency) {
                continue;
            }

            try {
                track = createAudioTrack(channelConfig, sampleRate, bufferSize, lowLatency);
                track.play();

                // Successfully created working AudioTrack. We're done here.
                LimeLog.info("Audio track configuration: "+bufferSize+" "+lowLatency);
                LimeLog.info("Audio track buffer: " + track.getBufferSizeInFrames() + " frames, min buffer "
                        + AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
                        + " bytes, max pending " + maxPendingMs + " ms");
                break;
            } catch (Exception e) {
                // Try to release the AudioTrack if we got far enough
                e.printStackTrace();
                try {
                    if (track != null) {
                        track.release();
                        track = null;
                    }
                } catch (Exception ignored) {}
            }
        }

        if (track == null) {
            // Couldn't create any audio track for playback
            return -2;
        }

        active = true;
        return 0;
    }

    private boolean shouldDropForBacklog(int pending, int thresholdMs, long now) {
        if (pending < thresholdMs) {
            return false;
        }
        droppedPackets++;
        lastDroppedPackets = droppedPackets;
        if (now - lastDropLogUptime > 1000) {
            lastDropLogUptime = now;
            LimeLog.info("Too much pending audio data: " + pending + " ms (dropped " + droppedPackets + " packets so far)");
        }
        return true;
    }

    private void recordAudioStats(int pending, long now) {
        lastPendingMs = pending;
        lastDroppedPackets = droppedPackets;
        if (track != null && (lastUnderrunRefreshUptime == 0 || now - lastUnderrunRefreshUptime >= UNDERRUN_REFRESH_MS)) {
            lastUnderrunRefreshUptime = now;
            try {
                int underruns = track.getUnderrunCount();
                lastUnderruns = underruns;
                // Quiet while nothing changes; log at most once per 10 s whenever counters moved
                if (now - lastUnderrunLogUptime > 10000
                        && (underruns != lastLoggedUnderruns || droppedPackets != lastLoggedDropped)) {
                    lastUnderrunLogUptime = now;
                    lastLoggedUnderruns = underruns;
                    lastLoggedDropped = droppedPackets;
                    LimeLog.info("AudioTrack underruns so far: " + underruns + ", dropped packets: " + droppedPackets);
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        if (activeEncoding != 0 || track == null) {
            return;
        }
        // Only queue up to maxPendingMs of pending audio data in addition to what AudioTrack is buffering for us.
        int pending = MoonBridge.getPendingAudioDuration();
        long now = android.os.SystemClock.uptimeMillis();
        if (!shouldDropForBacklog(pending, maxPendingMs, now)) {
            // This will block until the write is completed. That can cause a backlog
            // of pending audio data, so we do the above check to be able to bound
            // latency in that situation.
            track.write(audioData, 0, audioData.length);
        }
        recordAudioStats(pending, now);
    }

    private void writeEncodedFrame(byte[] audioData, int length, int encoding, long now) {
        int offset = 0;
        boolean reopened = false;
        while (offset < length && track != null) {
            int written = track.write(audioData, offset, length - offset);
            if (written > 0) {
                offset += written;
                continue;
            }
            if (written == AudioTrack.ERROR_DEAD_OBJECT && !reopened
                    && (lastReopenAttemptUptime == 0 || now - lastReopenAttemptUptime >= REOPEN_COOLDOWN_MS)) {
                lastReopenAttemptUptime = now;
                reopened = true;
                LimeLog.warning("Dolby AudioTrack DEAD_OBJECT; attempting reopen");
                if (setupEncodedTrack(encoding, length)) {
                    offset = 0;
                    continue;
                }
                return;
            }
            if (written < 0) {
                LimeLog.warning("Dolby AudioTrack write error: " + written);
            }
            break;
        }
    }

    @Override
    public void playEncodedAudio(byte[] audioData, int length, int encoding) {
        if (audioData == null || length <= 0 || length > audioData.length) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (activeEncoding != encoding || track == null) {
            if (lastReopenAttemptUptime != 0 && now - lastReopenAttemptUptime < REOPEN_COOLDOWN_MS) {
                return;
            }
            if (!setupEncodedTrack(encoding, length)) {
                lastReopenAttemptUptime = now;
                return;
            }
        }
        int pending = MoonBridge.getPendingAudioDuration();
        // Dolby frames are 32 ms each; allow 1 queued frame (drop at >= 64 ms) to bound latency
        int effectiveMaxPendingMs = Math.max(maxPendingMs, DOLBY_MAX_PENDING_MS);
        if (!shouldDropForBacklog(pending, effectiveMaxPendingMs, now)) {
            writeEncodedFrame(audioData, length, encoding, now);
        }
        recordAudioStats(pending, now);
    }

    @Override
    public void start() {
        if (enableAudioFx && track != null && activeEncoding == 0) {
            // Open an audio effect control session to allow equalizers to apply audio effects
            Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            i.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME);
            context.sendBroadcast(i);
        }
    }

    @Override
    public void stop() {
        if (enableAudioFx && track != null && activeEncoding == 0) {
            // Close our audio effect control session when we're stopping
            Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            context.sendBroadcast(i);
        }
    }

    @Override
    public void cleanup() {
        active = false;
        activeFormatLabel = "AudioTrack";
        activeEncoding = 0;
        encodedBufferSize = 0;
        releaseTrack();
    }

    public static boolean isActive() {
        return active;
    }

    public static String perfLine(Context context) {
        return context.getString(R.string.perf_overlay_audio,
                activeFormatLabel, lastPendingMs, lastUnderruns, lastDroppedPackets);
    }

    static void resetStaticStateForTest() {
        active = false;
        activeFormatLabel = "AudioTrack";
        lastPendingMs = 0;
        lastUnderruns = 0;
        lastDroppedPackets = 0;
        directPlaybackQuery = AudioManager::getDirectPlaybackSupport;
    }

    int getActiveEncoding() {
        return activeEncoding;
    }

    int getEncodedBufferSize() {
        return encodedBufferSize;
    }

    int getDroppedPackets() {
        return droppedPackets;
    }

    static String getActiveFormatLabel() {
        return activeFormatLabel;
    }
}
