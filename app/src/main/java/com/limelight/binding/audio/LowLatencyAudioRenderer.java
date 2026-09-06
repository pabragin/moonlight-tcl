package com.limelight.binding.audio;

import android.content.Context;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * Picks the audio output path: the native AAudio stream when allowed and it opens, otherwise the
 * classic AudioTrack renderer. While AAudio is active the native bridge feeds it directly and
 * {@link #playDecodedAudio(short[])} is never called.
 */
public class LowLatencyAudioRenderer implements AudioRenderer {
    private final Context context;
    private final boolean enableAudioFx;
    private final boolean preferAAudio;
    private final int maxPendingMs;

    private boolean usingAAudio;
    private AndroidAudioRenderer fallback;

    public LowLatencyAudioRenderer(Context context, boolean enableAudioFx, boolean preferAAudio, int maxPendingMs) {
        this.context = context;
        this.enableAudioFx = enableAudioFx;
        this.preferAAudio = preferAAudio;
        this.maxPendingMs = maxPendingMs;
    }

    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        // The system equalizer needs an AudioTrack session, so AAudio only applies without audio FX
        if (preferAAudio && !enableAudioFx) {
            int result = AAudioRenderer.setup(audioConfiguration.channelCount, sampleRate, samplesPerFrame, maxPendingMs);
            if (result == 0) {
                usingAAudio = true;
                LimeLog.info("Audio output: AAudio low-latency stream (" + audioConfiguration.channelCount + " ch, " + sampleRate + " Hz)");
                return 0;
            }
            LimeLog.warning("AAudio setup failed (" + result + "); falling back to AudioTrack");
            AAudioRenderer.cleanup();
        }
        fallback = new AndroidAudioRenderer(context, enableAudioFx, maxPendingMs);
        return fallback.setup(audioConfiguration, sampleRate, samplesPerFrame);
    }

    @Override
    public void start() {
        if (usingAAudio) {
            int result = AAudioRenderer.start();
            if (result != 0) {
                LimeLog.warning("AAudio start failed: " + result);
            }
        } else if (fallback != null) {
            fallback.start();
        }
    }

    @Override
    public void stop() {
        if (usingAAudio) {
            AAudioRenderer.stop();
        } else if (fallback != null) {
            fallback.stop();
        }
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        // Only reached on the AudioTrack path; the native bridge bypasses Java for AAudio
        if (fallback != null) {
            fallback.playDecodedAudio(audioData);
        }
    }

    @Override
    public void cleanup() {
        if (usingAAudio) {
            AAudioRenderer.cleanup();
            usingAAudio = false;
        } else if (fallback != null) {
            fallback.cleanup();
        }
    }
}
