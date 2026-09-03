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
    // Reaction frame detection. Under CBR encoding a static picture still costs ~1/3 of the target
    // bitrate, so the flip to white is only a few times bigger than the "quiet" frames.
    private static final double REACTION_SIZE_FACTOR = 2.5;
    private static final int REACTION_SIZE_MIN_DELTA_BYTES = 8000;
    // Sunshine/Apollo send a static picture at a reduced rate (typically fps/2); when the picture
    // changes the frame interval drops back to the full rate.
    private static final double REACTION_INTERVAL_FACTOR = 0.65;
    private static final long STATIC_INTERVAL_MIN_MS = 25;
    // A reaction cannot arrive faster than this after the press; earlier frames were already in flight
    private static final long MIN_REACTION_DELAY_MS = 15;
    private static final int DEBUG_FRAMES_AFTER_PRESS = 24;
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

    private final ArrayDeque<long[]> samples = new ArrayDeque<>(); // {total, hostNet, decodeRelease, inputStack, compositor(-1 = unknown)}
    // PTS of the reaction frame whose on-screen presentation we still wait for (guarded by samples)
    private long pendingPresentPtsUs = -1;
    private long pendingPresentReleaseUptime;
    private volatile String lastStats = "";

    // Running averages of frame size and inter-arrival interval (decoder thread)
    private double avgFrameBytes = -1;
    private double avgIntervalMs = -1;
    private long lastFrameArrival = 0;
    private int debugFramesLeft = 0;

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
            long hostNet = -1, inputStack = 0;
            synchronized (lock) {
                if (!measuring) {
                    return;
                }
                measuring = false;
                if (reactionPtsUs >= 0) {
                    hostNet = tArrival - t0Uptime;
                    inputStack = Math.max(0, t0Uptime - eventTimeUptime);
                }
                reactionPtsUs = -1;
            }
            if (hostNet >= 0) {
                LimeLog.info("Latency test: reaction frame arrived after " + hostNet + " ms but its render was not seen (frame pacing not 'lowest latency'?)");
                synchronized (samples) {
                    samples.addLast(new long[] { hostNet, hostNet, 0, inputStack });
                    while (samples.size() > MAX_SAMPLES) {
                        samples.removeFirst();
                    }
                }
                publish(activity.getString(R.string.latency_test_render_unmatched));
                return;
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
            t.debugFramesLeft = DEBUG_FRAMES_AFTER_PRESS;
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
        long now = SystemClock.uptimeMillis();
        String debug = null;
        synchronized (t.lock) {
            long interval = t.lastFrameArrival > 0 ? now - t.lastFrameArrival : -1;
            t.lastFrameArrival = now;

            boolean sizeJump = t.avgFrameBytes >= 0
                    && sizeBytes >= t.avgFrameBytes * REACTION_SIZE_FACTOR
                    && sizeBytes >= t.avgFrameBytes + REACTION_SIZE_MIN_DELTA_BYTES;
            boolean rateJump = t.avgIntervalMs >= STATIC_INTERVAL_MIN_MS && interval > 0
                    && interval <= t.avgIntervalMs * REACTION_INTERVAL_FACTOR;
            boolean candidate = t.measuring && t.reactionPtsUs < 0 && (now - t.t0Uptime) >= MIN_REACTION_DELAY_MS;

            if (t.measuring && t.debugFramesLeft > 0) {
                t.debugFramesLeft--;
                debug = String.format(Locale.ROOT, "Latency test frame: +%d ms size=%d avg=%.0f interval=%d avgInterval=%.1f%s",
                        now - t.t0Uptime, sizeBytes, t.avgFrameBytes, interval, t.avgIntervalMs,
                        candidate && (sizeJump || rateJump) ? (sizeJump ? " <- size jump" : " <- rate jump") : "");
            }

            if (candidate && (sizeJump || rateJump)) {
                t.reactionPtsUs = presentationTimeUs;
                t.tArrival = now;
            } else {
                // Learn the "quiet" statistics from frames that are not the reaction itself
                t.avgFrameBytes = t.avgFrameBytes < 0 ? sizeBytes : t.avgFrameBytes * 0.9 + sizeBytes * 0.1;
                if (interval > 0 && interval < 1000) {
                    t.avgIntervalMs = t.avgIntervalMs < 0 ? interval : t.avgIntervalMs * 0.9 + interval * 0.1;
                }
            }
        }
        if (debug != null) {
            LimeLog.info(debug);
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
            if (!t.measuring || t.reactionPtsUs < 0 || presentationTimeUs < t.reactionPtsUs) {
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
            t.samples.addLast(new long[] { total, hostNet, decodePresent, inputStack, -1 });
            while (t.samples.size() > MAX_SAMPLES) {
                t.samples.removeFirst();
            }
            t.pendingPresentPtsUs = presentationTimeUs;
            t.pendingPresentReleaseUptime = SystemClock.uptimeMillis();
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
                "Latency test: button->frame %d ms (input stack %d, host+net %d, decode+release %d) [%s]",
                total, inputStack, hostNet, decodePresent, stats));
        t.publish(null);
    }

    /** Called from the decoder's frame-rendered callback with the display time of a frame (any thread). */
    public static void onFramePresented(long presentationTimeUs, long presentUptimeMs) {
        LatencyTester t = instance;
        if (t == null) {
            return;
        }
        long compositor, total;
        synchronized (t.samples) {
            if (t.pendingPresentPtsUs < 0 || presentationTimeUs < t.pendingPresentPtsUs || t.samples.isEmpty()) {
                return;
            }
            long[] last = t.samples.getLast();
            last[4] = Math.max(0, presentUptimeMs - t.pendingPresentReleaseUptime);
            compositor = last[4];
            total = last[0];
            t.pendingPresentPtsUs = -1;
        }
        LimeLog.info(String.format(Locale.ROOT,
                "Latency test: compositor +%d ms (button->display %d ms)", compositor, total + compositor));
        t.publish(null);
    }

    private void publish(final String note) {
        final String text;
        synchronized (samples) {
            if (samples.isEmpty()) {
                text = note != null ? note : activity.getString(R.string.latency_test_waiting);
            } else {
                long[] last = samples.getLast();
                long min = Long.MAX_VALUE, max = 0, sum = 0, sumHostNet = 0, sumDecode = 0, sumInput = 0, nDecode = 0;
                long sumCompositor = 0, nCompositor = 0;
                for (long[] v : samples) {
                    min = Math.min(min, v[0]);
                    max = Math.max(max, v[0]);
                    sum += v[0];
                    sumHostNet += v[1];
                    sumInput += v[3];
                    if (v[2] > 0) {
                        sumDecode += v[2];
                        nDecode++;
                    }
                    if (v.length > 4 && v[4] >= 0) {
                        sumCompositor += v[4];
                        nCompositor++;
                    }
                }
                int n = samples.size();
                String stats = activity.getString(R.string.latency_test_overlay,
                        last[0], sum / n, min, max, n, sumInput / n, sumHostNet / n, nDecode > 0 ? sumDecode / nDecode : 0,
                        nCompositor > 0 ? sumCompositor / nCompositor : 0);
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
