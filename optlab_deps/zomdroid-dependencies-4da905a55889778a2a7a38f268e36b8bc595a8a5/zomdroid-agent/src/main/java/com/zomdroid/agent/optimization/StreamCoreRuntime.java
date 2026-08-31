package com.zomdroid.agent.optimization;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.locks.LockSupport;

/** Fail-open runtime for the independently switchable WorldStreamer mechanisms. */
public final class StreamCoreRuntime {
    private static volatile boolean wakeEnabled;
    private static volatile boolean queueFastEnabled;
    private static volatile boolean lookaheadEnabled;
    private static volatile Thread streamerThread;

    private static volatile boolean reflectionReady;
    private static Field comparatorPositions;
    private static Field playerArray;
    private static Field vectorX;
    private static Field vectorY;
    private static Method playerX;
    private static Method playerY;
    private static Method playerLastX;
    private static Method playerLastY;
    private static Method playerVehicle;
    private static Method vehicleSpeed;

    private StreamCoreRuntime() {}

    public static void configure(boolean wake, boolean queueFast, boolean lookahead) {
        wakeEnabled = wake;
        queueFastEnabled = queueFast;
        lookaheadEnabled = lookahead;
    }

    public static void disableWakeShape(int replacements) {
        wakeEnabled = false;
        FeatureCompatibility.fallback("STREAM_WAKE",
                "sleep_replacements=" + replacements);
    }

    public static void disableQueueShape(int replacements) {
        queueFastEnabled = false;
        FeatureCompatibility.fallback("STREAM_QUEUE_FAST",
                "sort_replacements=" + replacements);
    }

    public static void disableWake() {
        wakeEnabled = false;
        Thread target = streamerThread;
        if (target != null) LockSupport.unpark(target);
    }

    public static void disableQueueFast() {
        queueFastEnabled = false;
    }

    public static void disableLookahead() {
        lookaheadEnabled = false;
    }

    public static void idleWait(long millis) throws InterruptedException {
        if (!wakeEnabled || millis <= 0L) {
            Thread.sleep(millis);
            return;
        }
        Thread current = Thread.currentThread();
        streamerThread = current;
        if (Thread.interrupted()) throw new InterruptedException();
        LockSupport.parkNanos(current, millis * 1_000_000L);
        if (Thread.interrupted()) throw new InterruptedException();
        ProofRuntime.appliedOnce("STREAM_WAKE", "fixed_sleep_replaced_by_unparkable_wait");
    }

    public static void signal(Object worldStreamer) {
        if (!wakeEnabled) return;
        Thread target = streamerThread;
        if (target == null && worldStreamer != null) {
            try {
                Object value = worldStreamer.getClass().getField("worldStreamer").get(worldStreamer);
                if (value instanceof Thread) target = (Thread) value;
            } catch (Throwable ignored) {
                // The cached worker thread will be available after the first idle wait.
            }
        }
        if (target != null) LockSupport.unpark(target);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void sortForStreamer(List list, Comparator comparator) {
        if (!queueFastEnabled || !(list instanceof Stack) || list.size() < 2) {
            Collections.sort(list, comparator);
            return;
        }
        synchronized (list) {
            int size = list.size();
            int best = 0;
            for (int i = 1; i < size; i++) {
                // Collections.sort is stable and the caller removes the final (maximum) item.
                // Updating on equality preserves its "last equal wins" result.
                if (comparator.compare(list.get(i), list.get(best)) >= 0) best = i;
            }
            if (best != size - 1) {
                Object selected = list.remove(best);
                list.add(selected);
            }
        }
        ProofRuntime.appliedOnce("STREAM_QUEUE_FAST", "stack_full_sort_replaced_by_stable_linear_select");
    }

    public static void adjustLookahead(Object comparator) {
        if (!lookaheadEnabled || comparator == null) return;
        try {
            ensureReflection(comparator);
            Object[] positions = (Object[]) comparatorPositions.get(comparator);
            Object[] players = (Object[]) playerArray.get(null);
            int count = Math.min(positions.length, players.length);
            boolean changed = false;
            float maxLookahead = 10.0f;
            for (int i = 0; i < count; i++) {
                Object player = players[i];
                Object pos = positions[i];
                if (player == null || pos == null) continue;
                float x = ((Number) playerX.invoke(player)).floatValue();
                float y = ((Number) playerY.invoke(player)).floatValue();
                float dx = x - ((Number) playerLastX.invoke(player)).floatValue();
                float dy = y - ((Number) playerLastY.invoke(player)).floatValue();
                float length = (float) Math.sqrt(dx * dx + dy * dy);
                if (length < 0.0001f) continue;

                float speedKmh = 0.0f;
                Object vehicle = playerVehicle.invoke(player);
                if (vehicle != null) {
                    speedKmh = Math.abs(((Number) vehicleSpeed.invoke(vehicle)).floatValue());
                }
                float lookahead = 10.0f + Math.max(0.0f, speedKmh - 18.0f) * 0.30f;
                lookahead = Math.max(10.0f, Math.min(28.0f, lookahead));
                float inv = 1.0f / length;
                vectorX.setFloat(pos, x + dx * inv * lookahead);
                vectorY.setFloat(pos, y + dy * inv * lookahead);
                maxLookahead = Math.max(maxLookahead, lookahead);
                changed |= lookahead > 10.01f;
            }
            if (changed) {
                ProofRuntime.appliedOnce("STREAM_VELOCITY_ETA", "bounded_lookahead_max=" + maxLookahead);
            }
        } catch (Throwable error) {
            lookaheadEnabled = false;
            FeatureCompatibility.fallback("STREAM_VELOCITY_ETA",
                    "runtime=" + error.getClass().getSimpleName());
        }
    }

    private static synchronized void ensureReflection(Object comparator) throws Exception {
        if (reflectionReady) return;
        Class<?> comparatorClass = comparator.getClass();
        ClassLoader loader = comparatorClass.getClassLoader();
        comparatorPositions = comparatorClass.getDeclaredField("pos");
        comparatorPositions.setAccessible(true);
        Class<?> vector = Class.forName("zombie.iso.Vector2", false, loader);
        vectorX = vector.getField("x");
        vectorY = vector.getField("y");
        Class<?> player = Class.forName("zombie.characters.IsoPlayer", false, loader);
        playerArray = player.getField("players");
        playerX = player.getMethod("getX");
        playerY = player.getMethod("getY");
        playerLastX = player.getMethod("getLastX");
        playerLastY = player.getMethod("getLastY");
        playerVehicle = player.getMethod("getVehicle");
        Class<?> vehicle = Class.forName("zombie.vehicles.BaseVehicle", false, loader);
        vehicleSpeed = vehicle.getMethod("getCurrentAbsoluteSpeedKmHour");
        reflectionReady = true;
    }
}
