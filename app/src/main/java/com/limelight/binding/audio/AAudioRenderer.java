package com.limelight.binding.audio;

import android.content.Context;

import com.limelight.R;

/**
 * Thin Java side of the native AAudio output path (aaudio_renderer.c). Decoded PCM never passes
 * through Java: moonlight-common-c's audio thread writes into the native ring directly.
 */
public final class AAudioRenderer {
    static {
        // libmoonlight-core is loaded by MoonBridge; make sure that happened before our natives are bound
        try {
            Class.forName("com.limelight.nvstream.jni.MoonBridge");
        } catch (ClassNotFoundException ignored) {
        }
    }

    private AAudioRenderer() {
    }

    private static native int nativeSetup(int channelCount, int sampleRate, int samplesPerFrame, int maxPendingMs);
    private static native int nativeStart();
    private static native void nativeStop();
    private static native void nativeCleanup();
    private static native boolean nativeIsActive();
    private static native int[] nativeGetStats();

    /** @return 0 on success, negative on failure (the caller falls back to AudioTrack). */
    public static int setup(int channelCount, int sampleRate, int samplesPerFrame, int maxPendingMs) {
        try {
            return nativeSetup(channelCount, sampleRate, samplesPerFrame, maxPendingMs);
        } catch (Throwable t) {
            return -101;
        }
    }

    public static int start() {
        return nativeStart();
    }

    public static void stop() {
        nativeStop();
    }

    public static void cleanup() {
        nativeCleanup();
    }

    public static boolean isActive() {
        try {
            return nativeIsActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /** One line for the performance overlay: latency, underruns, dropped packets. */
    public static String perfLine(Context context) {
        int[] s = nativeGetStats();
        if (s == null || s.length < 4) {
            return "";
        }
        return context.getString(R.string.perf_overlay_audio, "AAudio", s[0], s[1], s[2]);
    }
}
