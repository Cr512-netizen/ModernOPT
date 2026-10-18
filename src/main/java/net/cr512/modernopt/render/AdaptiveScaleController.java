package net.cr512.modernopt.render;

/** Smoothed adaptive scale controller independent of Minecraft and OpenGL. */
public final class AdaptiveScaleController {
    private static final long UPDATE_INTERVAL_NANOS = 500_000_000L;
    private static final long MAX_ACCEPTED_FRAME_NANOS = 1_000_000_000L;
    private static final double EMA_TIME_CONSTANT_NANOS = 250_000_000.0;
    private static final double REDUCE_RATIO = 1.05;
    private static final double RECOVER_RATIO = 0.90;

    private long previousFrameNanos;
    private boolean hasPreviousFrame;
    private double averageFrameNanos;
    private long elapsedNanos;

    public int update(long nowNanos, int currentPercent, int targetFps,
                      int minimumPercent, int maximumPercent,
                      boolean paused, boolean cameraMoving) {
        int min = Math.min(minimumPercent, maximumPercent);
        int max = Math.max(minimumPercent, maximumPercent);
        int current = clamp(currentPercent, min, max);
        if (targetFps <= 0) {
            resetTiming();
            return max;
        }
        if (paused) {
            resetTiming();
            return current;
        }
        if (!hasPreviousFrame) {
            previousFrameNanos = nowNanos;
            hasPreviousFrame = true;
            return current;
        }

        long frameNanos = nowNanos - previousFrameNanos;
        previousFrameNanos = nowNanos;
        if (frameNanos <= 0 || frameNanos > MAX_ACCEPTED_FRAME_NANOS) {
            // Loading, debugger pauses and GC stalls must not drag the scale down.
            resetTiming();
            return current;
        }

        double alpha = 1.0 - Math.exp(-frameNanos / EMA_TIME_CONSTANT_NANOS);
        averageFrameNanos = averageFrameNanos == 0.0
            ? frameNanos : averageFrameNanos + alpha * (frameNanos - averageFrameNanos);
        if (!cameraMoving) {
            // Do not accumulate a resize deadline while the camera is still.
            // Otherwise the first moving frame can trigger an immediate scale jump.
            elapsedNanos = 0L;
            return current;
        }
        elapsedNanos = Math.min(UPDATE_INTERVAL_NANOS, elapsedNanos + frameNanos);
        if (elapsedNanos < UPDATE_INTERVAL_NANOS) return current;

        double ratio = averageFrameNanos * targetFps / 1_000_000_000.0;
        int next = current;
        if (ratio > REDUCE_RATIO) next = Math.max(min, current - 1);
        else if (ratio < RECOVER_RATIO) next = Math.min(max, current + 1);
        elapsedNanos = 0L;
        return next;
    }

    public void resetTiming() {
        previousFrameNanos = 0L;
        hasPreviousFrame = false;
        averageFrameNanos = 0.0;
        elapsedNanos = 0L;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
