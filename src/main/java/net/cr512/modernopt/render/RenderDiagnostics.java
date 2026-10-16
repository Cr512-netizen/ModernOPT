package net.cr512.modernopt.render;

import net.cr512.modernopt.client.BenchmarkRecorder;
import net.cr512.modernopt.config.ModernOptConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CPU-side render timings and render-target telemetry.
 * Deliberately avoids OpenGL timer queries, which can synchronize the GPU and
 * conflict with shader packs. Timings are CPU wall-clock durations, not GPU time.
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

    private static volatile double lastFrameCpuMs;
    private static volatile double lastPostCpuMs;
    private static volatile int lastWindowWidth;
    private static volatile int lastWindowHeight;
    private static volatile int lastWorldWidth;
    private static volatile int lastWorldHeight;
    private static volatile boolean lastShaderPackInUse;
    private static volatile int lastUpscalerMode;

    private RenderDiagnostics() {}

    public static void beginFrame() {
        if (!ModernOptConfig.ENABLED.get()
            || (!ModernOptConfig.PROFILING_ENABLED.get() && !BenchmarkRecorder.isRecording())) {
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
        lastWindowWidth = nativeWidth;
        lastWindowHeight = nativeHeight;
        lastWorldWidth = renderWidth;
        lastWorldHeight = renderHeight;
        lastShaderPackInUse = shaderPack;
        lastUpscalerMode = upscalerMode;
        if (frameStartedAt == 0L) return;

        long now = System.nanoTime();
        long worldPassEnd = postStartedAt == 0L ? now : postStartedAt;
        long frameNanos = Math.max(0L, worldPassEnd - frameStartedAt);
        long postNanos = postStartedAt == 0L ? 0L : Math.max(0L, now - postStartedAt);
        lastFrameCpuMs = frameNanos / 1_000_000.0;
        lastPostCpuMs = postNanos / 1_000_000.0;
        frameStartedAt = 0L;
        postStartedAt = 0L;

        totalFrameNanos += frameNanos;
        totalPostNanos += postNanos;
        maxFrameNanos = Math.max(maxFrameNanos, frameNanos);
        maxPostNanos = Math.max(maxPostNanos, postNanos);
        frames++;

        if (!ModernOptConfig.PROFILING_ENABLED.get()
            || (lastReportAt != 0L && now - lastReportAt < REPORT_INTERVAL_NANOS)) return;
        long averageFrame = totalFrameNanos / Math.max(1, frames);
        long averagePost = totalPostNanos / Math.max(1, frames);
        LOGGER.info("Render diagnostics (CPU wall time, not GPU time): frames={}, world-pass avg/max={}/{} ms, post-process avg/max={}/{} ms, output={}x{}, world={}x{}, upscaler={}, enabled={}, shaderPack={}",
            frames, millis(averageFrame), millis(maxFrameNanos), millis(averagePost), millis(maxPostNanos),
            nativeWidth, nativeHeight, renderWidth, renderHeight, upscalerMode, enabled, shaderPack);

        lastReportAt = now;
        totalFrameNanos = 0L;
        totalPostNanos = 0L;
        maxFrameNanos = 0L;
        maxPostNanos = 0L;
        frames = 0;
    }

    public static double lastFrameCpuMs() { return lastFrameCpuMs; }
    public static double lastPostCpuMs() { return lastPostCpuMs; }
    public static int lastWindowWidth() { return lastWindowWidth; }
    public static int lastWindowHeight() { return lastWindowHeight; }
    public static int lastWorldWidth() { return lastWorldWidth; }
    public static int lastWorldHeight() { return lastWorldHeight; }
    public static boolean lastShaderPackInUse() { return lastShaderPackInUse; }
    public static int lastUpscalerMode() { return lastUpscalerMode; }

    private static double millis(long nanos) {
        return Math.round(nanos / 100_000.0) / 10.0;
    }
}
