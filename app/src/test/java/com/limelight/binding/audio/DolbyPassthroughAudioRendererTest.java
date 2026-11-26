package com.limelight.binding.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.TestLogSuppressor;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.shadows.ShadowGameManager;
import com.limelight.shadows.ShadowMoonBridge;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAudioTrack;

import java.util.ArrayList;
import java.util.List;

@Config(sdk = {34}, shadows = {
        ShadowMoonBridge.class,
        ShadowGameManager.class,
        DolbyPassthroughAudioRendererTest.ShadowAAudioRenderer.class
})
@RunWith(RobolectricTestRunner.class)
public class DolbyPassthroughAudioRendererTest {

    @Implements(value = AAudioRenderer.class, isInAndroidSdk = false)
    public static class ShadowAAudioRenderer {
        static boolean setupCalled;
        static boolean stopCalled;
        static boolean cleanupCalled;

        static void reset() {
            setupCalled = false;
            stopCalled = false;
            cleanupCalled = false;
        }

        @Implementation
        protected static void __staticInitializer__() {
        }

        @Implementation
        protected static int setup(int channelCount, int sampleRate, int samplesPerFrame, int maxPendingMs) {
            setupCalled = true;
            return 0;
        }

        @Implementation
        protected static int start() {
            return 0;
        }

        @Implementation
        protected static void stop() {
            stopCalled = true;
        }

        @Implementation
        protected static void cleanup() {
            cleanupCalled = true;
        }
    }

    private Context context;

    @BeforeClass
    public static void silenceLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        ShadowMoonBridge.setPendingAudioDurationForTest(0);
        ShadowAudioTrack.clearAllowedNonPcmEncodings();
        ShadowAAudioRenderer.reset();
        AndroidAudioRenderer.resetStaticStateForTest();
    }

    @After
    public void tearDown() {
        ShadowMoonBridge.setPendingAudioDurationForTest(0);
        ShadowAudioTrack.clearAllowedNonPcmEncodings();
        ShadowAAudioRenderer.reset();
        AndroidAudioRenderer.resetStaticStateForTest();
    }

    private static byte[] sampleSyncframe(int length, byte bsidByte) {
        byte[] frame = new byte[length];
        frame[0] = 0x0B;
        frame[1] = 0x77;
        frame[5] = bsidByte;
        return frame;
    }

    @Test
    public void probeSupportedDolbyCodecs_detectsBitstreamOffloadAndAudioFxDecline() {
        MoonBridge.AudioConfiguration surround51 = new MoonBridge.AudioConfiguration(6, 0x3F);

        AndroidAudioRenderer.directPlaybackQuery = (fmt, attrs) -> AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED;
        assertEquals(MoonBridge.AUDIO_CODEC_OPUS,
                AndroidAudioRenderer.probeSupportedDolbyCodecs(context, surround51));

        // AC-3 via bitstream (USAGE_MEDIA fallback), E-AC-3 via offload (TV internal MS12 speakers)
        AndroidAudioRenderer.directPlaybackQuery = (fmt, attrs) -> {
            if (fmt.getEncoding() == AudioFormat.ENCODING_AC3
                    && attrs.getUsage() == AudioAttributes.USAGE_MEDIA) {
                return AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED;
            }
            if (fmt.getEncoding() == AudioFormat.ENCODING_E_AC3
                    && attrs.getUsage() == AudioAttributes.USAGE_GAME) {
                return AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED;
            }
            return AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED;
        };
        assertEquals(MoonBridge.AUDIO_CODEC_AC3 | MoonBridge.AUDIO_CODEC_EAC3,
                AndroidAudioRenderer.probeSupportedDolbyCodecs(context, surround51));

        // LowLatencyAudioRenderer must decline Dolby when audio FX equalizer is enabled
        LowLatencyAudioRenderer fxRenderer = new LowLatencyAudioRenderer(context, true, true, 40);
        assertEquals(MoonBridge.AUDIO_CODEC_OPUS, fxRenderer.getSupportedDolbyCodecs(surround51));

        LowLatencyAudioRenderer directRenderer = new LowLatencyAudioRenderer(context, false, true, 40);
        assertEquals(MoonBridge.AUDIO_CODEC_AC3 | MoonBridge.AUDIO_CODEC_EAC3,
                directRenderer.getSupportedDolbyCodecs(surround51));
    }

    @Test
    public void playEncodedAudio_enforces64msOverrideWhenMaxPendingIs20ms() {
        ShadowAudioTrack.addAllowedNonPcmEncoding(AudioFormat.ENCODING_E_AC3);

        // maxPendingMs = 20 ms (< 32 ms) proves the 64 ms Dolby backlog override is actually enforced
        AndroidAudioRenderer renderer = new AndroidAudioRenderer(context, false, 20);
        assertEquals(0, renderer.setupEncoded(new MoonBridge.AudioConfiguration(6, 0x3F), 48000, AudioFormat.ENCODING_E_AC3));
        renderer.start();
        assertTrue(AndroidAudioRenderer.isActive());

        byte[] eac3Frame = sampleSyncframe(1536, (byte) (16 << 3));

        // 32 ms pending (1 Dolby frame) exceeds maxPendingMs (20 ms) but must NOT drop under the 64 ms Dolby cap
        ShadowMoonBridge.setPendingAudioDurationForTest(32);
        renderer.playEncodedAudio(eac3Frame, eac3Frame.length, AudioFormat.ENCODING_E_AC3);

        assertEquals(AudioFormat.ENCODING_E_AC3, renderer.getActiveEncoding());
        assertEquals(1536, renderer.getEncodedBufferSize());
        assertEquals("E-AC-3 5.1", AndroidAudioRenderer.getActiveFormatLabel());
        assertEquals(0, renderer.getDroppedPackets());
        assertTrue(AndroidAudioRenderer.perfLine(context).contains("E-AC-3 5.1"));

        // 64 ms pending reaches the 64 ms Dolby backlog limit and should drop stale frames cleanly
        ShadowMoonBridge.setPendingAudioDurationForTest(64);
        renderer.playEncodedAudio(eac3Frame, eac3Frame.length, AudioFormat.ENCODING_E_AC3);
        assertEquals(1, renderer.getDroppedPackets());

        renderer.stop();
        renderer.cleanup();
        assertFalse(AndroidAudioRenderer.isActive());
    }

    @Test
    public void setupEncoded_supportsAc3FormatAndPerfLine() {
        ShadowAudioTrack.addAllowedNonPcmEncoding(AudioFormat.ENCODING_AC3);

        AndroidAudioRenderer renderer = new AndroidAudioRenderer(context, false, 40);
        assertEquals(0, renderer.setupEncoded(new MoonBridge.AudioConfiguration(6, 0x3F), 48000, AudioFormat.ENCODING_AC3));
        renderer.start();

        byte[] ac3Frame = sampleSyncframe(2560, (byte) (8 << 3));
        renderer.playEncodedAudio(ac3Frame, ac3Frame.length, AudioFormat.ENCODING_AC3);

        assertEquals(AudioFormat.ENCODING_AC3, renderer.getActiveEncoding());
        assertEquals(2560, renderer.getEncodedBufferSize());
        assertEquals("AC-3 5.1", AndroidAudioRenderer.getActiveFormatLabel());
        assertTrue(AndroidAudioRenderer.perfLine(context).contains("AC-3 5.1"));

        renderer.stop();
        renderer.cleanup();
    }

    @Test
    public void setupEncoded_returnsErrorWhenDolbyTrackRejected() {
        AndroidAudioRenderer renderer = new AndroidAudioRenderer(context, false, 40);
        assertEquals(-2, renderer.setupEncoded(new MoonBridge.AudioConfiguration(6, 0x3F), 48000, AudioFormat.ENCODING_E_AC3));

        assertEquals(0, renderer.getActiveEncoding());
        assertFalse(AndroidAudioRenderer.isActive());

        renderer.stop();
        renderer.cleanup();
    }

    @Test
    public void setupEncoded_retriesGameThenMediaAtSmallBufferWithoutDuplicates() {
        List<String> attempts = new ArrayList<>();
        AudioTrack mockTrack = mock(AudioTrack.class);

        AndroidAudioRenderer renderer = new AndroidAudioRenderer(context, false, 40) {
            @Override
            AudioTrack createEncodedAudioTrack(int encoding, int channelMask, int trackSampleRate,
                                               int bufferSize, boolean gameUsage) {
                attempts.add((gameUsage ? "GAME:" : "MEDIA:") + bufferSize);
                if (gameUsage) {
                    throw new UnsupportedOperationException("USAGE_GAME rejected");
                }
                return mockTrack;
            }
        };

        assertEquals(0, renderer.setupEncoded(new MoonBridge.AudioConfiguration(6, 0x3F), 48000, AudioFormat.ENCODING_AC3));
        assertEquals(2, attempts.size());
        assertEquals("GAME:2560", attempts.get(0));
        assertEquals("MEDIA:2560", attempts.get(1));
        verify(mockTrack).play();

        renderer.cleanup();
    }

    @Test
    public void playEncodedAudio_reopensOnDeadObjectAndCompletesShortWrite() {
        byte[] ac3Frame = sampleSyncframe(2560, (byte) (8 << 3));
        AudioTrack deadTrack = mock(AudioTrack.class);
        AudioTrack recoveredTrack = mock(AudioTrack.class);

        when(deadTrack.write(ac3Frame, 0, 2560)).thenReturn(AudioTrack.ERROR_DEAD_OBJECT);
        when(recoveredTrack.write(ac3Frame, 0, 2560)).thenReturn(1000);
        when(recoveredTrack.write(ac3Frame, 1000, 1560)).thenReturn(1560);

        List<AudioTrack> tracks = new ArrayList<>();
        tracks.add(deadTrack);
        tracks.add(recoveredTrack);

        AndroidAudioRenderer renderer = new AndroidAudioRenderer(context, false, 20) {
            @Override
            AudioTrack createEncodedAudioTrack(int encoding, int channelMask, int trackSampleRate,
                                               int bufferSize, boolean gameUsage) {
                return tracks.remove(0);
            }
        };

        assertEquals(0, renderer.setupEncoded(new MoonBridge.AudioConfiguration(6, 0x3F), 48000, AudioFormat.ENCODING_AC3));
        renderer.playEncodedAudio(ac3Frame, ac3Frame.length, AudioFormat.ENCODING_AC3);

        verify(deadTrack).release();
        verify(recoveredTrack).play();
        verify(recoveredTrack).write(ac3Frame, 0, 2560);
        verify(recoveredTrack).write(ac3Frame, 1000, 1560);
        assertTrue(AndroidAudioRenderer.isActive());

        renderer.cleanup();
    }

    @Test
    public void lowLatencyAudioRenderer_setupEncodedSkipsAAudioCompletely() {
        ShadowAudioTrack.addAllowedNonPcmEncoding(AudioFormat.ENCODING_E_AC3);

        LowLatencyAudioRenderer renderer = new LowLatencyAudioRenderer(context, false, true, 40);
        assertEquals(0, renderer.setupEncoded(new MoonBridge.AudioConfiguration(6, 0x3F), 48000, AudioFormat.ENCODING_E_AC3));
        renderer.start();

        assertFalse(ShadowAAudioRenderer.setupCalled);
        assertFalse(ShadowAAudioRenderer.stopCalled);
        assertFalse(ShadowAAudioRenderer.cleanupCalled);

        AndroidAudioRenderer fallback = renderer.getFallbackRendererForTest();
        assertNotNull(fallback);
        assertEquals(AudioFormat.ENCODING_E_AC3, fallback.getActiveEncoding());
        assertEquals(1536, fallback.getEncodedBufferSize());
        assertTrue(AndroidAudioRenderer.isActive());

        byte[] eac3Frame = sampleSyncframe(1536, (byte) (16 << 3));
        renderer.playEncodedAudio(eac3Frame, eac3Frame.length, AudioFormat.ENCODING_E_AC3);

        renderer.stop();
        renderer.cleanup();
        assertFalse(AndroidAudioRenderer.isActive());
    }
}
