package com.zomdroid.agent.optimization;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Bounded adaptive park for the capped PZ main-loop.
 *
 * It learns the actual frameStep cadence instead of assuming a fixed FPS. The
 * transformed yield call is after MainThread.m_contextLock is released.
 */
public final class PacingRuntime {
    private static final long MIN_CAPPED_PERIOD_NS = 2_000_000L;
    private static final long MAX_PERIOD_NS = 100_000_000L;

    private static volatile boolean enabled;
    private static volatile long spinWindowNs = 150_000L;
    private static volatile long maxParkNs = 500_000L;
    private static volatile long lastFrameNs;
    private static volatile long estimatedPeriodNs;
    private static volatile long nextDeadlineNs;
    private static volatile Thread mainThread;
    private static volatile long lastMetricsNs;

    private static final AtomicLong frameSteps = new AtomicLong();
    private static final AtomicLong parkCount = new AtomicLong();
    private static final AtomicLong parkNanos = new AtomicLong();
    private static final AtomicLong unparkCount = new AtomicLong();
    private static final AtomicLong yieldCount = new AtomicLong();

    private PacingRuntime() {}

    public static void configureFromProperties() {
        spinWindowNs = boundedProperty("zomdroid.optlab.pacing.spin.us",
                150L, 50L, 1_000L) * 1_000L;
        maxParkNs = boundedProperty("zomdroid.optlab.pacing.max.park.us",
                500L, 50L, 2_000L) * 1_000L;
        enabled = true;
        System.out.println("[ZD-OPT-LAB-PACING] configured spinUs=" + (spinWindowNs / 1_000L)
                + " maxParkUs=" + (maxParkNs / 1_000L));
    }

    public static void disable() {
        enabled = false;
        Thread thread = mainThread;
        if (thread != null) LockSupport.unpark(thread);
    }

    public static void onFrameStep() {
        if (!enabled) return;
        long now = System.nanoTime();
        long previous = lastFrameNs;
        lastFrameNs = now;
        mainThread = Thread.currentThread();
        frameSteps.incrementAndGet();

        if (previous != 0L) {
            long observed = now - previous;
            if (observed >= MIN_CAPPED_PERIOD_NS && observed <= MAX_PERIOD_NS) {
                long estimate = estimatedPeriodNs;
                estimatedPeriodNs = estimate == 0L
                        ? observed
                        : ((estimate * 7L) + observed) / 8L;
                nextDeadlineNs = now + estimatedPeriodNs;
            } else if (observed < MIN_CAPPED_PERIOD_NS) {
                // Uncapped/high-rate path: yield exactly as legacy.
                estimatedPeriodNs = 0L;
                nextDeadlineNs = now;
            }
        }
    }

    /** Replaces exactly one Thread.yield() in MainThread.mainLoop(). */
    public static void yieldOrPark() {
        if (!enabled) {
            Thread.yield();
            return;
        }
        if (mainThread == null) mainThread = Thread.currentThread();

        long estimate = estimatedPeriodNs;
        long remaining = nextDeadlineNs - System.nanoTime();
        if (estimate < MIN_CAPPED_PERIOD_NS || remaining <= spinWindowNs) {
            yieldCount.incrementAndGet();
            Thread.yield();
            return;
        }

        long duration = Math.min(maxParkNs, remaining - spinWindowNs);
        if (duration <= 0L) {
            yieldCount.incrementAndGet();
            Thread.yield();
            return;
        }
        long started = System.nanoTime();
        LockSupport.parkNanos(duration);
        long actual = Math.max(0L, System.nanoTime() - started);
        parkCount.incrementAndGet();
        parkNanos.addAndGet(actual);
        maybeLogMetrics();
    }

    public static void onMainThreadWorkQueued() {
        if (!enabled) return;
        Thread thread = mainThread;
        if (thread != null) {
            unparkCount.incrementAndGet();
            LockSupport.unpark(thread);
        }
    }

    private static void maybeLogMetrics() {
        long now = System.nanoTime();
        long last = lastMetricsNs;
        if (last != 0L && now - last < 30_000_000_000L) return;
        lastMetricsNs = now;
        System.out.println(String.format(Locale.ROOT,
                "[ZD-OPT-LAB-PACING] frames=%d parks=%d parkMs=%.1f unparks=%d yields=%d periodMs=%.3f",
                frameSteps.get(), parkCount.get(), parkNanos.get() / 1_000_000.0,
                unparkCount.get(), yieldCount.get(), estimatedPeriodNs / 1_000_000.0));
    }

    private static long boundedProperty(String name, long fallback, long minimum, long maximum) {
        try {
            long value = Long.parseLong(System.getProperty(name, Long.toString(fallback)));
            return Math.max(minimum, Math.min(maximum, value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
