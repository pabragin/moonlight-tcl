package com.limelight.utils;

import android.content.Context;
import android.os.PerformanceHintManager;

import com.limelight.LimeLog;

import java.util.ArrayList;
import java.util.List;

/**
 * ADPF (Android Performance Hint Manager, API 31+) session for the streaming hot threads: tells the
 * power HAL the frame deadline and how long each frame actually took, so it can keep CPU frequency
 * and placement up for those threads instead of reacting to average load. Everything is best-effort:
 * a HAL that declines leaves this inert and says so once in the log.
 */
public final class PerformanceHints implements AutoCloseable {
    private final PerformanceHintManager manager;
    private final long preferredRateNs;
    private final List<Integer> tids = new ArrayList<>();
    private PerformanceHintManager.Session session;
    private long targetNs;
    private volatile long lastReportNs;
    private boolean declined;

    private PerformanceHints(PerformanceHintManager manager, long preferredRateNs, float fps) {
        this.manager = manager;
        this.preferredRateNs = Math.max(preferredRateNs, 0);
        this.targetNs = targetForFps(fps);
    }

    private static long targetForFps(float fps) {
        return fps > 1 ? (long)(1_000_000_000.0 / fps) : 16_666_667L;
    }

    /** @return null when the device or HAL does not support hint sessions. */
    public static PerformanceHints create(Context context, float fps) {
        try {
            PerformanceHintManager manager = context.getSystemService(PerformanceHintManager.class);
            if (manager == null) {
                LimeLog.info("ADPF: no PerformanceHintManager on this device");
                return null;
            }
            long rate = manager.getPreferredUpdateRateNanos();
            if (rate < 0) {
                LimeLog.info("ADPF: hint sessions not supported by the power HAL");
                return null;
            }
            return new PerformanceHints(manager, rate, fps);
        } catch (Throwable t) {
            LimeLog.info("ADPF unavailable: " + t);
            return null;
        }
    }

    /** Register the calling thread (call from inside that thread with Process.myTid()). */
    public synchronized void addThread(int tid) {
        if (declined || tids.contains(tid)) {
            return;
        }
        tids.add(tid);
        int[] all = new int[tids.size()];
        for (int i = 0; i < all.length; i++) {
            all[i] = tids.get(i);
        }
        try {
            if (session == null) {
                session = manager.createHintSession(all, targetNs);
                if (session == null) {
                    declined = true;
                    LimeLog.info("ADPF: createHintSession returned null (HAL declined); hints disabled");
                    return;
                }
            } else {
                session.setThreads(all);
            }
            LimeLog.info("ADPF: hint session threads=" + tids + " target=" + (targetNs / 1000) + " us, preferred update rate "
                    + (preferredRateNs / 1000) + " us");
        } catch (Throwable t) {
            LimeLog.warning("ADPF: " + t);
        }
    }

    public synchronized void setTargetFps(float fps) {
        long target = targetForFps(fps);
        if (target == targetNs) {
            return;
        }
        targetNs = target;
        if (session != null) {
            try {
                session.updateTargetWorkDuration(target);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Report how long the just-presented frame took (decoder submit -> release), rate-limited. */
    public void reportWorkNanos(long workNs) {
        PerformanceHintManager.Session s = session;
        if (s == null) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastReportNs < preferredRateNs) {
            return;
        }
        lastReportNs = now;
        if (workNs < 1) {
            workNs = 1;
        } else if (workNs > 1_000_000_000L) {
            workNs = 1_000_000_000L;
        }
        try {
            s.reportActualWorkDuration(workNs);
        } catch (Throwable ignored) {
        }
    }

    public boolean isActive() {
        return session != null;
    }

    @Override
    public synchronized void close() {
        if (session != null) {
            try {
                session.close();
            } catch (Throwable ignored) {
            }
            session = null;
        }
    }
}
