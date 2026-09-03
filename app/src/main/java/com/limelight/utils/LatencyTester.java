package com.limelight.utils;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceView;
import android.widget.TextView;

import com.limelight.LimeLog;
import com.limelight.R;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Button-to-frame latency measurement for A/B testing client builds.
 *
 * Usage: on the host open tools/latency-test.html full screen (black page that turns white while a
 * gamepad button is held). In the client enable "Latency test mode" and press A/B/X/Y.
 *
 * Detection does not read pixels (PixelCopy of the video surface takes hundreds of ms on MediaTek
 * TVs). Instead: a static black page produces tiny video frames; the flip to white produces one
 * frame that is many times larger. The first such frame after the button press is the host's
 * reaction. Its arrival time gives input stack + network + host; the moment the renderer hands
 * that frame (matched by presentation timestamp) to the display surface gives the total.
 * The TV panel's own delay is not included, which is what makes builds comparable on one TV.
 */
public final class LatencyTester {
    private static final int TIMEOUT_MS = 2000;
    private static final int MIN_REACTION_FRAME_BYTES = 4000;
    private static final int REACTION_SIZE_FACTOR = 6;
    private static final int MAX_SAMPLES = 30;

    /** Supplies a short description of the stream state (decode time, RTT, display Hz) for logging. */
    public interface StatsProvider {
        String describe();
    }

    private static volatile LatencyTester instance;

    private final Activity activity;
    private final TextView overlay;
    private final StatsProvider statsProvider;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ArrayDeque<long[]> samples = new ArrayDeque<>(); // {total, hostNet, decodePresent, inputStack}
    private volatile String lastStats = "";

    // Running average of frame sizes (decoder thread)
    private double avgFrameBytes = -1;

    // Measurement in flight
    private final Object lock = new Object();
    private boolean measuring;
    private long t0Uptime;          // button event handled by the app
    private long eventTimeUptime;   // button event time from the input stack
    private long tArrival;          // reaction frame received from the network
    private long reactionPtsUs = -1;
    private final Runnable timeout = new Runnable() {
        @Override
        public void run() {
            synchronized (lock) {
                if (!measuring) {
                    return;
                }
                measuring = false;
                reactionPtsUs = -1;
            }
            LimeLog.info("Latency test: no reaction frame within " + TIMEOUT_MS + " ms");
            publish(activity.getString(R.string.latency_test_timeout));
        }
    };

    private LatencyTester(Activity activity, TextView overlay, StatsProvider statsProvider) {
        this.activity = activity;
        this.overlay = overlay;
        this.statsProvider = statsProvider;
    }

    public static void start(Activity activity, SurfaceView unusedSurfaceView, TextView overlay) {
        start(activity, unusedSurfaceView, overlay, null);
    }

    public static void start(Activity activity, SurfaceView unusedSurfaceView, TextView overlay, StatsProvider statsProvider) {
        stop();
        instance = new LatencyTester(activity, overlay, statsProvider);
        overlay.setVisibility(android.view.View.VISIBLE);
        overlay.setText(activity.getString(R.string.latency_test_waiting));
        LimeLog.info("Latency test mode enabled");
    }

    public static void stop() {
        LatencyTester t = instance;
        instance = null;
        if (t != null) {
            t.mainHandler.removeCallbacks(t.timeout);
        }
    }

    public static boolean isEnabled() {
        return instance != null;
    }

    /** Called from the input path when a face button transitions to pressed. */
    public static void onButtonDown(long eventTimeUptimeMs) {
        LatencyTester t = instance;
        if (t == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        synchronized (t.lock) {
            if (t.measuring) {
                return;
            }
            t.measuring = true;
            t.t0Uptime = now;
            t.eventTimeUptime = eventTimeUptimeMs;
            t.reactionPtsUs = -1;
        }
        t.mainHandler.removeCallbacks(t.timeout);
        t.mainHandler.postDelayed(t.timeout, TIMEOUT_MS);
    }

    /** Called by the decoder for every received video frame (any thread). */
    public static void onFrameReceived(int sizeBytes, long presentationTimeUs) {
        LatencyTester t = instance;
        if (t == null) {
            return;
        }
        synchronized (t.lock) {
            boolean big = t.avgFrameBytes >= 0 && sizeBytes >= Math.max(MIN_REACTION_FRAME_BYTES, t.avgFrameBytes * REACTION_SIZE_FACTOR);
            if (t.measuring && t.reactionPtsUs < 0 && big) {
                t.reactionPtsUs = presentationTimeUs;
                t.tArrival = SystemClock.uptimeMillis();
            } else {
                // Only learn the "quiet" frame size from frames that are not the reaction itself
                t.avgFrameBytes = t.avgFrameBytes < 0 ? sizeBytes : t.avgFrameBytes * 0.9 + sizeBytes * 0.1;
            }
        }
    }

    /** Called by the renderer when a frame is handed to the display surface (any thread). */
    public static void onFrameRendered(long presentationTimeUs) {
        LatencyTester t = instance;
        if (t == null) {
            return;
        }
        long total, hostNet, decodePresent, inputStack;
        synchronized (t.lock) {
            if (!t.measuring || t.reactionPtsUs != presentationTimeUs) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            total = now - t.t0Uptime;
            hostNet = t.tArrival - t.t0Uptime;
            decodePresent = now - t.tArrival;
            inputStack = Math.max(0, t.t0Uptime - t.eventTimeUptime);
            t.measuring = false;
            t.reactionPtsUs = -1;
        }
        t.mainHandler.removeCallbacks(t.timeout);
        synchronized (t.samples) {
            t.samples.addLast(new long[] { total, hostNet, decodePresent, inputStack });
            while (t.samples.size() > MAX_SAMPLES) {
                t.samples.removeFirst();
            }
        }
        String stats = "";
        if (t.statsProvider != null) {
            try {
                stats = t.statsProvider.describe();
            } catch (Exception e) {
                stats = "";
            }
        }
        t.lastStats = stats;
        LimeLog.info(String.format(Locale.ROOT,
                "Latency test: button->frame %d ms (input stack %d, host+net %d, decode+present %d) [%s]",
                total, inputStack, hostNet, decodePresent, stats));
        t.publish(null);
    }

    private void publish(final String note) {
        final String text;
        synchronized (samples) {
            if (samples.isEmpty()) {
                text = note != null ? note : activity.getString(R.string.latency_test_waiting);
            } else {
                long[] last = samples.getLast();
                long min = Long.MAX_VALUE, max = 0, sum = 0;
                for (long[] v : samples) {
                    min = Math.min(min, v[0]);
                    max = Math.max(max, v[0]);
                    sum += v[0];
                }
                String stats = activity.getString(R.string.latency_test_overlay,
                        last[0], sum / samples.size(), min, max, samples.size(), last[3], last[1], last[2]);
                if (!lastStats.isEmpty()) {
                    stats = stats + "\n" + lastStats;
                }
                text = note != null ? stats + "\n" + note : stats;
            }
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                overlay.setText(text);
            }
        });
    }
}
