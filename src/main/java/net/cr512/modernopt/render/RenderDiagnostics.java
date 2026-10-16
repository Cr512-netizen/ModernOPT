package net.cr512.modernopt.render;

import net.cr512.modernopt.client.BenchmarkRecorder;
import net.cr512.modernopt.config.ModernOptConfig;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import com.mojang.blaze3d.pipeline.RenderTarget;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Render-path diagnostics. CPU timings are wall-clock timings around command
 * submission; GPU timings use asynchronous timestamp queries and are only read
 * after GL_QUERY_RESULT_AVAILABLE, never by blocking for a result.
 */
public final class RenderDiagnostics {
    private static final Logger LOGGER = LoggerFactory.getLogger("ModernOPT");
    private static final long REPORT_INTERVAL_NANOS = 5_000_000_000L;
    private static final int QUERY_SLOTS = 8;

    private static long frameStartedAt;
    private static long postStartedAt;
    private static long lastReportAt;
    private static long totalFrameNanos;
    private static long totalPostNanos;
    private static long maxFrameNanos;
    private static long maxPostNanos;
    private static int frames;
    private static int gpuSamples;
    private static double totalGpuWorldMs;
    private static double totalGpuPostMs;
    private static double maxGpuWorldMs;
    private static double maxGpuPostMs;

    private static int[][] timestampQueries;
    private static final boolean[] queryPending = new boolean[QUERY_SLOTS];
    private static int activeQuerySlot = -1;
    private static int nextQuerySlot;
    private static boolean gpuQueriesUnavailableLogged;

    private static volatile double lastFrameCpuMs;
    private static volatile double lastPostCpuMs;
    private static volatile double lastGpuWorldMs = -1.0;
    private static volatile double lastGpuPostMs = -1.0;
    private static volatile int lastWindowWidth;
    private static volatile int lastWindowHeight;
    private static volatile int lastWorldWidth;
    private static volatile int lastWorldHeight;
    private static volatile boolean lastShaderPackInUse;
    private static volatile int lastUpscalerMode;
    private static volatile boolean lastMainTargetRestored = true;
    private static volatile String lastUpscalerPath = "not sampled";
    private static volatile String lastAuxiliaryTargets = "not sampled";
    private static volatile String lastIrisTargets = "not sampled";

    private RenderDiagnostics() {}

    public static void beginFrame() {
        pollGpuQueries();
        if (!ModernOptConfig.PROFILING_ENABLED.get() && !BenchmarkRecorder.isRecording()) {
            frameStartedAt = 0L;
            postStartedAt = 0L;
            activeQuerySlot = -1;
            return;
        }
        frameStartedAt = System.nanoTime();
        postStartedAt = 0L;
        activeQuerySlot = beginGpuFrame();
    }

    public static void beginPostProcess() {
        if (frameStartedAt != 0L) postStartedAt = System.nanoTime();
        if (activeQuerySlot >= 0) {
            GL33.glQueryCounter(timestampQueries[activeQuerySlot][1], GL33.GL_TIMESTAMP);
        }
    }

    /** Called by optional Iris mixins after its render-target object is constructed/resized. */
    public static void recordIrisTargets(Object irisTargets) {
        if (!ModernOptConfig.PROFILING_ENABLED.get() || irisTargets == null) return;
        List<String> found = new ArrayList<>();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        for (Class<?> type = irisTargets.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(irisTargets);
                    collectTargetDimensions(field.getName(), value, found, seen);
                } catch (RuntimeException | IllegalAccessException ignored) {
                    // Diagnostics must never break Iris resource creation.
                }
            }
        }
        lastIrisTargets = found.isEmpty() ? "no RenderTarget fields found in " + irisTargets.getClass().getSimpleName()
            : String.join("|", found);
    }

    private static void collectTargetDimensions(String name, Object value, List<String> found,
                                                IdentityHashMap<Object, Boolean> seen) {
        if (value == null || seen.put(value, Boolean.TRUE) != null) return;
        if (value instanceof RenderTarget target) {
            found.add(name + "=" + target.width + "x" + target.height);
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object element : iterable) collectTargetDimensions(name + "[" + index++ + "]", element, found, seen);
            return;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) collectTargetDimensions(name + "[" + i + "]", Array.get(value, i), found, seen);
        }
    }

    public static void recordFrameState(boolean mainTargetRestored, String upscalerPath, String auxiliaryTargets) {
        lastMainTargetRestored = mainTargetRestored;
        lastUpscalerPath = upscalerPath == null ? "unknown" : upscalerPath;
        lastAuxiliaryTargets = (auxiliaryTargets == null ? "unknown" : auxiliaryTargets) + "; irisTargets=" + lastIrisTargets;
    }

    public static void endFrame(int nativeWidth, int nativeHeight, int renderWidth, int renderHeight,
                                int upscalerMode, boolean enabled, boolean shaderPack) {
        lastWindowWidth = nativeWidth;
        lastWindowHeight = nativeHeight;
        lastWorldWidth = renderWidth;
        lastWorldHeight = renderHeight;
        lastShaderPackInUse = shaderPack;
        lastUpscalerMode = upscalerMode;

        long now = System.nanoTime();
        if (activeQuerySlot >= 0) {
            // Frames without a ModernOPT post-process still need all three query
            // timestamps issued; otherwise that ring slot could remain pending forever.
            if (postStartedAt == 0L) {
                GL33.glQueryCounter(timestampQueries[activeQuerySlot][1], GL33.GL_TIMESTAMP);
            }
            GL33.glQueryCounter(timestampQueries[activeQuerySlot][2], GL33.GL_TIMESTAMP);
            queryPending[activeQuerySlot] = true;
            activeQuerySlot = -1;
        }
        if (frameStartedAt == 0L) return;

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
        LOGGER.info("Render diagnostics: frames={}, CPU world avg/max={}/{} ms, CPU post avg/max={}/{} ms, GPU world avg/max={}/{} ms, GPU post avg/max={}/{} ms (GPU samples={}), output={}x{}, main-world={}x{}, upscaler={}, path={}, enabled={}, shaderPack={}, mainTargetRestored={}, auxiliaryTargets={}",
            frames, millis(averageFrame), millis(maxFrameNanos), millis(averagePost), millis(maxPostNanos),
            gpuSamples == 0 ? "n/a" : decimal(totalGpuWorldMs / gpuSamples),
            gpuSamples == 0 ? "n/a" : decimal(maxGpuWorldMs),
            gpuSamples == 0 ? "n/a" : decimal(totalGpuPostMs / gpuSamples),
            gpuSamples == 0 ? "n/a" : decimal(maxGpuPostMs), gpuSamples,
            nativeWidth, nativeHeight, renderWidth, renderHeight, upscalerMode, lastUpscalerPath,
            enabled, shaderPack, lastMainTargetRestored, lastAuxiliaryTargets);
        if (!lastMainTargetRestored) {
            LOGGER.warn("Main render target was not restored to the frame's original target. Capture this log and latest.log; this is a render-state restoration failure.");
        }

        lastReportAt = now;
        totalFrameNanos = 0L;
        totalPostNanos = 0L;
        maxFrameNanos = 0L;
        maxPostNanos = 0L;
        frames = 0;
        gpuSamples = 0;
        totalGpuWorldMs = 0.0;
        totalGpuPostMs = 0.0;
        maxGpuWorldMs = 0.0;
        maxGpuPostMs = 0.0;
    }

    private static int beginGpuFrame() {
        if (!gpuTimingSupported()) return -1;
        for (int attempt = 0; attempt < QUERY_SLOTS; attempt++) {
            int slot = (nextQuerySlot + attempt) % QUERY_SLOTS;
            if (queryPending[slot]) continue;
            nextQuerySlot = (slot + 1) % QUERY_SLOTS;
            GL33.glQueryCounter(timestampQueries[slot][0], GL33.GL_TIMESTAMP);
            return slot;
        }
        return -1;
    }

    private static boolean gpuTimingSupported() {
        try {
            if (!GL.getCapabilities().OpenGL33) {
                if (!gpuQueriesUnavailableLogged) {
                    gpuQueriesUnavailableLogged = true;
                    LOGGER.info("Asynchronous GPU timestamp queries are unavailable; CPU-only diagnostics remain active.");
                }
                return false;
            }
            if (timestampQueries == null) {
                timestampQueries = new int[QUERY_SLOTS][3];
                for (int i = 0; i < QUERY_SLOTS; i++) {
                    for (int j = 0; j < 3; j++) timestampQueries[i][j] = GL15.glGenQueries();
                }
            }
            return true;
        } catch (IllegalStateException | LinkageError unavailable) {
            if (!gpuQueriesUnavailableLogged) {
                gpuQueriesUnavailableLogged = true;
                LOGGER.info("Could not initialize asynchronous GPU timestamps; CPU-only diagnostics remain active.", unavailable);
            }
            return false;
        }
    }

    private static void pollGpuQueries() {
        if (timestampQueries == null) return;
        for (int slot = 0; slot < QUERY_SLOTS; slot++) {
            if (!queryPending[slot]) continue;
            boolean ready = true;
            for (int query : timestampQueries[slot]) {
                if (GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                    ready = false;
                    break;
                }
            }
            if (!ready) continue;
            long start = GL33.glGetQueryObjectui64(timestampQueries[slot][0], GL15.GL_QUERY_RESULT);
            long worldEnd = GL33.glGetQueryObjectui64(timestampQueries[slot][1], GL15.GL_QUERY_RESULT);
            long frameEnd = GL33.glGetQueryObjectui64(timestampQueries[slot][2], GL15.GL_QUERY_RESULT);
            if (worldEnd >= start && frameEnd >= worldEnd) {
                double worldMs = (worldEnd - start) / 1_000_000.0;
                double postMs = (frameEnd - worldEnd) / 1_000_000.0;
                lastGpuWorldMs = worldMs;
                lastGpuPostMs = postMs;
                totalGpuWorldMs += worldMs;
                totalGpuPostMs += postMs;
                maxGpuWorldMs = Math.max(maxGpuWorldMs, worldMs);
                maxGpuPostMs = Math.max(maxGpuPostMs, postMs);
                gpuSamples++;
            }
            queryPending[slot] = false;
        }
    }

    public static double lastFrameCpuMs() { return lastFrameCpuMs; }
    public static double lastPostCpuMs() { return lastPostCpuMs; }
    public static double lastGpuWorldMs() { return lastGpuWorldMs; }
    public static double lastGpuPostMs() { return lastGpuPostMs; }
    public static int lastWindowWidth() { return lastWindowWidth; }
    public static int lastWindowHeight() { return lastWindowHeight; }
    public static int lastWorldWidth() { return lastWorldWidth; }
    public static int lastWorldHeight() { return lastWorldHeight; }
    public static boolean lastShaderPackInUse() { return lastShaderPackInUse; }
    public static int lastUpscalerMode() { return lastUpscalerMode; }
    public static boolean lastMainTargetRestored() { return lastMainTargetRestored; }
    public static String lastUpscalerPath() { return lastUpscalerPath; }
    public static String lastAuxiliaryTargets() { return lastAuxiliaryTargets; }

    private static double millis(long nanos) {
        return Math.round(nanos / 100_000.0) / 10.0;
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
