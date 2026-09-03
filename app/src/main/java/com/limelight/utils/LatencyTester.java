package com.limelight.utils;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.widget.TextView;

import androidx.annotation.RequiresApi;

import com.limelight.LimeLog;
import com.limelight.R;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Button-to-frame latency measurement for A/B testing client builds.
 *
 * Usage: on the host open tools/latency-test.html full screen (black page that turns white while a
 * gamepad button is held). In the client enable "Latency test mode" and press A/B/X/Y. When the
 * button event reaches the app we remember the time and start polling a small corner of the video
 * surface with PixelCopy; the first frame whose brightness jumps is the host's reaction.
 * The reported value is input-stack + network + host + decode time, i.e. everything up to the
 * frame being available on the stream surface. The TV panel's own latency is not included, which
 * is exactly what makes two client builds comparable on the same TV.
 */
@RequiresApi(api = Build.VERSION_CODES.N)
public final class LatencyTester {
    private static final int PROBE_SIZE = 8;
    private static final int POLL_INTERVAL_MS = 2;
    private static final int TIMEOUT_MS = 2000;
    private static final int LUMA_DELTA_THRESHOLD = 80;
    private static final int MAX_SAMPLES = 30;

    private static volatile LatencyTester instance;

    private final Activity activity;
    private final SurfaceView surfaceView;
    private final TextView overlay;
    private final HandlerThread thread;
    private final Handler handler;
    private final Bitmap probe = Bitmap.createBitmap(PROBE_SIZE, PROBE_SIZE, Bitmap.Config.ARGB_8888);
    // Probe the top-left corner of the stream surface (in surface pixels)
    private final Rect probeRect = new Rect(16, 16, 80, 80);

    private final ArrayDeque<Long> samples = new ArrayDeque<>();
    private long lastInputStackMs;

    // Measurement in flight (accessed on the tester thread only, except the volatile flag)
    private volatile boolean measuring;
    private long t0Uptime;
    private long eventTimeUptime;
    private long deadline;
    private int baselineLuma = -1;

    private LatencyTester(Activity activity, SurfaceView surfaceView, TextView overlay) {
        this.activity = activity;
        this.surfaceView = surfaceView;
        this.overlay = overlay;
        this.thread = new HandlerThread("LatencyTester");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    public static void start(Activity activity, SurfaceView surfaceView, TextView overlay) {
        stop();
        instance = new LatencyTester(activity, surfaceView, overlay);
        overlay.setVisibility(android.view.View.VISIBLE);
        overlay.setText(activity.getString(R.string.latency_test_waiting));
        LimeLog.info("Latency test mode enabled");
    }

    public static void stop() {
        LatencyTester t = instance;
        instance = null;
        if (t != null) {
            t.thread.quitSafely();
        }
    }

    public static boolean isEnabled() {
        return instance != null;
    }

    /** Called from the input path when a face button transitions to pressed. */
    public static void onButtonDown(final long eventTimeUptimeMs) {
        final LatencyTester t = instance;
        if (t == null || t.measuring) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        t.measuring = true;
        t.handler.post(new Runnable() {
            @Override
            public void run() {
                t.t0Uptime = now;
                t.eventTimeUptime = eventTimeUptimeMs;
                t.deadline = now + TIMEOUT_MS;
                t.baselineLuma = -1;
                t.poll();
            }
        });
    }

    private void poll() {
        if (instance != this) {
            return;
        }
        try {
            PixelCopy.request(surfaceView, probeRect, probe, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override
                public void onPixelCopyFinished(int copyResult) {
                    onProbe(copyResult);
                }
            }, handler);
        } catch (IllegalArgumentException e) {
            // Surface not valid (yet); retry until the deadline
            onProbe(PixelCopy.ERROR_SOURCE_INVALID);
        }
    }

    private void onProbe(int copyResult) {
        if (instance != this) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (copyResult == PixelCopy.SUCCESS) {
            int luma = averageLuma();
            if (baselineLuma < 0) {
                baselineLuma = luma;
            } else if (Math.abs(luma - baselineLuma) >= LUMA_DELTA_THRESHOLD) {
                long latency = now - t0Uptime;
                lastInputStackMs = Math.max(0, t0Uptime - eventTimeUptime);
                synchronized (samples) {
                    samples.addLast(latency);
                    while (samples.size() > MAX_SAMPLES) {
                        samples.removeFirst();
                    }
                }
                LimeLog.info(String.format(Locale.ROOT, "Latency test: button->frame %d ms (input stack %d ms, luma %d -> %d)",
                        latency, lastInputStackMs, baselineLuma, luma));
                measuring = false;
                publish(null);
                return;
            }
        }
        if (now >= deadline) {
            LimeLog.info("Latency test: no brightness change within " + TIMEOUT_MS + " ms");
            measuring = false;
            publish(activity.getString(R.string.latency_test_timeout));
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                poll();
            }
        }, copyResult == PixelCopy.SUCCESS ? POLL_INTERVAL_MS : 5);
    }

    private int averageLuma() {
        long sum = 0;
        for (int y = 0; y < PROBE_SIZE; y++) {
            for (int x = 0; x < PROBE_SIZE; x++) {
                int c = probe.getPixel(x, y);
                sum += (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
            }
        }
        return (int) (sum / (PROBE_SIZE * PROBE_SIZE));
    }

    private void publish(final String note) {
        final String text;
        synchronized (samples) {
            if (samples.isEmpty()) {
                text = note != null ? note : activity.getString(R.string.latency_test_waiting);
            } else {
                long last = samples.getLast(), min = Long.MAX_VALUE, max = 0, sum = 0;
                for (long v : samples) {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                    sum += v;
                }
                String stats = activity.getString(R.string.latency_test_overlay,
                        last, sum / samples.size(), min, max, samples.size(), lastInputStackMs);
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
