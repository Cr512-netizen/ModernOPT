package net.cr512.modernopt.render;

import net.cr512.modernopt.config.ModernOptConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in CPU-side render diagnostics. This deliberately avoids OpenGL timer
 * queries and glGetError polling, which can interfere with other render mods
 * or introduce synchronization stalls.
 */
public final class RenderDiagnostics {
    private static final Logger LOGGER = LoggerFactory.getLogger("ModernOPT");
    private static final long REPORT_INTERVAL_NANOS = 5_000_000_000L;

    private static long frameStartedAt;
    private static long postStartedAt;
    private static long lastReportAt;
    private static long totalFrameNanos;
    private static long totalPostNanos;
    private static long maxFrameNanos;
    private static long maxPostNanos;
    private static int frames;

    private RenderDiagnostics() {}

    public static void beginFrame() {
        if (!ModernOptConfig.ENABLED.get() || !ModernOptConfig.PROFILING_ENABLED.get()) {
            frameStartedAt = 0L;
            postStartedAt = 0L;
            return;
        }
        frameStartedAt = System.nanoTime();
        postStartedAt = 0L;
    }

    public static void beginPostProcess() {
        if (frameStartedAt != 0L) postStartedAt = System.nanoTime();
    }

    public static void endFrame(int nativeWidth, int nativeHeight, int renderWidth, int renderHeight,
                                int upscalerMode, boolean enabled, boolean shaderPack) {
        if (frameStartedAt == 0L) return;
        long now = System.nanoTime();
        long frameNanos = now - frameStartedAt;
        long postNanos = postStartedAt == 0L ? 0L : Math.max(0L, now - postStartedAt);
        frameStartedAt = 0L;
        postStartedAt = 0L;

        totalFrameNanos += frameNanos;
        totalPostNanos += postNanos;
        maxFrameNanos = Math.max(maxFrameNanos, frameNanos);
        maxPostNanos = Math.max(maxPostNanos, postNanos);
        frames++;

        if (lastReportAt != 0L && now - lastReportAt < REPORT_INTERVAL_NANOS) return;
        long averageFrame = totalFrameNanos / Math.max(1, frames);
        long averagePost = totalPostNanos / Math.max(1, frames);
        LOGGER.info("Render diagnostics (CPU only): frames={}, frame avg/max={}/{} ms, post avg/max={}/{} ms, output={}x{}, world={}x{}, upscaler={}, enabled={}, shaderPack={}",
            frames, millis(averageFrame), millis(maxFrameNanos), millis(averagePost), millis(maxPostNanos),
            nativeWidth, nativeHeight, renderWidth, renderHeight, upscalerMode, enabled, shaderPack);

        lastReportAt = now;
        totalFrameNanos = 0L;
        totalPostNanos = 0L;
        maxFrameNanos = 0L;
        maxPostNanos = 0L;
        frames = 0;
    }

    private static double millis(long nanos) {
        return Math.round(nanos / 100_000.0) / 10.0;
    }
}
