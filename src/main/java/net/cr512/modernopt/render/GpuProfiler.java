// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.render;

import net.cr512.modernopt.ModernOPT;
import org.lwjgl.opengl.GL33C;

/**
 * Lightweight GPU timer for the phases of a ModernOPT frame: the Iris/vanilla world render at
 * the internal resolution, the shader pack's shadow and composite chains, and ModernOPT's own
 * upscale passes.
 *
 * <p>Uses {@code GL_TIMESTAMP} counters rather than a single {@code GL_TIME_ELAPSED} query
 * because OpenGL only allows one elapsed-time query of each target at a time, while these
 * sections nest (shadow and composite render inside the world pass). Counters just record a
 * point on the GPU timeline, so they can overlap freely.</p>
 *
 * <p>Diagnostic only; costs a few query objects and no synchronisation. The log prints the
 * measured stages as a tree and checks that the children of the world pass cannot add up to more
 * than the world pass itself, which catches double counting and hook misplacement.</p>
 */
public final class GpuProfiler {

    public static final int WORLD = 0;
    public static final int UPSCALE = 1;
    public static final int SHADOW = 2;
    public static final int COMPOSITE = 3;
    public static final int FINAL = 4;
    public static final int TERRAIN = 5;
    public static final int SKY = 6;
    public static final int HUD = 7;
    public static final int PRESENT = 8;
    public static final int FRAME = 9;
    private static final int SECTIONS = 10;
    private static final int RING = 3;

    /**
     * Timers are a development aid: they cost GL queries every frame, so they are only created
     * when the benchmark harness is enabled with {@code -Dmodernopt.bench=true}.
     */
    private static final boolean ENABLED = Boolean.getBoolean("modernopt.bench")
            && !Boolean.getBoolean("modernopt.bench.nogpu");

    /** Whether the stage timers are active; throughput runs disable them. */
    public static boolean isEnabled() {
        return ENABLED;
    }

    private static final int[][] beginQueries = new int[SECTIONS][RING];
    private static final int[][] endQueries = new int[SECTIONS][RING];
    private static final int[] pending = new int[SECTIONS];
    private static final double[] accumMs = new double[SECTIONS];
    private static final int[] samples = new int[SECTIONS];
    /** State-wide totals for the benchmark JSON; only {@link #reset()} clears them. */
    private static final double[] totalMs = new double[SECTIONS];
    private static final int[] totalSamples = new int[SECTIONS];
    private static final int[] depth = new int[SECTIONS];
    private static boolean active;
    private static long lastLogNanos = System.nanoTime();

    /** Shadow counters at the previous log, so every line covers exactly one reporting window. */
    private static int lastShadowDrawn;
    private static int lastShadowHeld;
    private static int lastForcedCamera;
    private static int lastForcedTime;
    private static int lastForcedCaster;
    private static boolean nestedWarningLogged;

    /** Bench-only diagnostic: CPU time spent submitting the world section. */
    private static long cpuWorldStart;
    private static double cpuWorldAccumMs;
    private static int cpuWorldSamples;

    /** CPU wall time spent inside {@code Window#updateDisplay}, dominated by the buffer swap. */
    private static long presentStartNanos;
    private static double presentCpuAccumMs;
    private static int presentCpuSamples;

    private GpuProfiler() {
    }

    private static void ensureQueries() {
        if (active) {
            return;
        }
        for (int section = 0; section < SECTIONS; section++) {
            for (int i = 0; i < RING; i++) {
                beginQueries[section][i] = GL33C.glGenQueries();
                endQueries[section][i] = GL33C.glGenQueries();
            }
        }
        active = true;
    }

    /** Records the start of a section on the GPU timeline. Nested calls only start one counter. */
    public static void begin(int section) {
        if (!ENABLED) {
            return;
        }
        if (depth[section]++ != 0) {
            return;
        }
        ensureQueries();
        GL33C.glQueryCounter(beginQueries[section][pending[section] % RING], GL33C.GL_TIMESTAMP);
    }

    /** Records the end of a section and folds a finished frame into the running average. */
    public static void end(int section) {
        if (!ENABLED || depth[section] == 0) {
            return;
        }
        if (--depth[section] != 0) {
            return;
        }
        if (!active) {
            return;
        }
        int slot = pending[section] % RING;
        GL33C.glQueryCounter(endQueries[section][slot], GL33C.GL_TIMESTAMP);

        if (pending[section] >= RING - 1) {
            // Two frames of latency: the counters below are surely resolved, so this never blocks.
            int poll = (pending[section] - (RING - 1) + RING) % RING;
            long start = GL33C.glGetQueryObjectui64(beginQueries[section][poll], GL33C.GL_QUERY_RESULT);
            long stop = GL33C.glGetQueryObjectui64(endQueries[section][poll], GL33C.GL_QUERY_RESULT);
            if (stop > start) {
                double elapsed = (stop - start) / 1_000_000.0;
                accumMs[section] += elapsed;
                samples[section]++;
                totalMs[section] += elapsed;
                totalSamples[section]++;
            }
        }
        pending[section]++;
    }

    /** Bench-only diagnostic: marks the start of the world section on the CPU timeline. */
    public static void beginCpuWorld() {
        cpuWorldStart = System.nanoTime();
    }

    /** Bench-only diagnostic: accumulates the CPU time spent submitting the world section. */
    public static void endCpuWorld() {
        if (cpuWorldStart != 0) {
            cpuWorldAccumMs += (System.nanoTime() - cpuWorldStart) / 1_000_000.0;
            cpuWorldSamples++;
            cpuWorldStart = 0;
        }
    }

    /** Wraps {@code Window#updateDisplay}: the swap blocks on vsync and is not a GPU query. */
    public static void beginPresent() {
        if (ENABLED) {
            presentStartNanos = System.nanoTime();
        }
    }

    public static void endPresent() {
        if (!ENABLED || presentStartNanos == 0L) {
            return;
        }
        presentCpuAccumMs += (System.nanoTime() - presentStartNanos) / 1_000_000.0;
        presentCpuSamples++;
        presentStartNanos = 0L;
    }

    /** Logs averaged phase timings roughly every 2 seconds. */
    public static void maybeLog() {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastLogNanos < 2_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        if (!active) {
            return;
        }

        ShadowHoldStats stats = RenderPipeline.shadowHoldStats();
        int windowDrawn = stats.drawn() - lastShadowDrawn;
        int windowHeld = stats.held() - lastShadowHeld;
        int windowForcedCamera = stats.forcedCamera() - lastForcedCamera;
        int windowForcedTime = stats.forcedTime() - lastForcedTime;
        int windowForcedCaster = stats.forcedCaster() - lastForcedCaster;
        if (windowDrawn < 0 || windowHeld < 0) {
            // The counters were reset between logs (new world, interval change): this window is
            // just the part accumulated since the reset.
            windowDrawn = stats.drawn();
            windowHeld = stats.held();
            windowForcedCamera = stats.forcedCamera();
            windowForcedTime = stats.forcedTime();
            windowForcedCaster = stats.forcedCaster();
        }
        lastShadowDrawn = stats.drawn();
        lastShadowHeld = stats.held();
        lastForcedCamera = stats.forcedCamera();
        lastForcedTime = stats.forcedTime();
        lastForcedCaster = stats.forcedCaster();
        int windowTotal = windowDrawn + windowHeld;
        double shadowDrawnMs = value(SHADOW);
        // Same window as the rest of the tree: shadow samples are drawn frames, so dividing by
        // world samples gives exactly the per-frame amortized shadow cost.
        double shadowAmortizedMs = samples[WORLD] == 0 ? 0
                : accumMs[SHADOW] / samples[WORLD];

        StringBuilder tree = new StringBuilder(512);
        tree.append("[BENCH][GPU] frame ").append(average(FRAME)).append(" ms (")
                .append(samples[FRAME]).append(")\n");
        tree.append("[BENCH][GPU]   world ").append(average(WORLD)).append(" ms (")
                .append(samples[WORLD]).append(")\n");
        tree.append("[BENCH][GPU]     terrain ").append(average(TERRAIN)).append(" ms (")
                .append(samples[TERRAIN]).append(")\n");
        tree.append("[BENCH][GPU]     sky ").append(average(SKY)).append(" ms (")
                .append(samples[SKY]).append(")\n");
        tree.append("[BENCH][GPU]     shadow ").append(format(shadowDrawnMs))
                .append(" ms drawn, ").append(format(shadowAmortizedMs)).append(" ms amortized @")
                .append(net.cr512.modernopt.compat.ShadowMapResolution.describe()).append('\n');
        tree.append("[BENCH][GPU]     composite ").append(average(COMPOSITE)).append(" ms (")
                .append(samples[COMPOSITE]).append(")\n");
        tree.append("[BENCH][GPU]     final ").append(average(FINAL)).append(" ms (")
                .append(samples[FINAL]).append(")\n");
        tree.append("[BENCH][GPU]   upscale ").append(average(UPSCALE)).append(" ms (")
                .append(samples[UPSCALE]).append(")\n");
        tree.append("[BENCH][GPU]   hud ").append(average(HUD)).append(" ms (")
                .append(samples[HUD]).append(")\n");
        tree.append("[BENCH][GPU]   presentGpu ").append(average(PRESENT)).append(" ms (")
                .append(samples[PRESENT]).append(")\n");
        tree.append("[BENCH][GPU] presentCpu ").append(averagePresentCpu()).append(" ms (")
                .append(presentCpuSamples).append(")\n");
        tree.append("[BENCH][GPU] shadow held ").append(windowHeld).append('/')
                .append(windowTotal).append(" (")
                .append(windowTotal == 0 ? 0 : Math.round(windowHeld * 100.0 / windowTotal))
                .append("%) interval=").append(stats.interval())
                .append(" forced camera=").append(windowForcedCamera)
                .append(" time=").append(windowForcedTime)
                .append(" caster=").append(windowForcedCaster);
        ModernOPT.LOG.info(tree.toString());

        double worldChildren = samples[WORLD] == 0 ? 0
                : (accumMs[TERRAIN] + accumMs[SKY] + accumMs[SHADOW]
                        + accumMs[COMPOSITE] + accumMs[FINAL]) / samples[WORLD];
        if (samples[WORLD] > 0
                && worldChildren > value(WORLD) + 0.05) {
            if (!nestedWarningLogged) {
                nestedWarningLogged = true;
                ModernOPT.LOG.warn("[BENCH][GPU] nested stages exceed world: {} ms in children "
                                + "vs {} ms in world; a hook is double-counting or placed outside "
                                + "the world pass", format(worldChildren), average(WORLD));
            }
        }

        ModernOPT.LOG.info("[BENCH][CPU] worldCpu {} ms ({}), presentCpu {} ms ({})",
                format(cpuWorldSamples == 0 ? 0 : cpuWorldAccumMs / cpuWorldSamples),
                cpuWorldSamples, averagePresentCpu(), presentCpuSamples);
        cpuWorldAccumMs = 0;
        cpuWorldSamples = 0;
        presentCpuAccumMs = 0;
        presentCpuSamples = 0;
        for (int section = 0; section < SECTIONS; section++) {
            accumMs[section] = 0;
            samples[section] = 0;
        }
    }

    private static String average(int section) {
        return format(value(section));
    }

    private static String averagePresentCpu() {
        return format(presentCpuSamples == 0 ? 0 : presentCpuAccumMs / presentCpuSamples);
    }

    private static double value(int section) {
        return samples[section] == 0 ? 0 : accumMs[section] / samples[section];
    }

    private static String format(double value) {
        return String.format("%.2f", value);
    }

    public static void reset() {
        for (int section = 0; section < SECTIONS; section++) {
            accumMs[section] = 0;
            samples[section] = 0;
            totalMs[section] = 0;
            totalSamples[section] = 0;
        }
        presentCpuAccumMs = 0;
        presentCpuSamples = 0;
        lastLogNanos = System.nanoTime();
    }

    /** Deletes the GL query objects on shutdown. */
    public static void shutdown() {
        if (!active) {
            return;
        }
        for (int section = 0; section < SECTIONS; section++) {
            for (int i = 0; i < RING; i++) {
                GL33C.glDeleteQueries(beginQueries[section][i]);
                GL33C.glDeleteQueries(endQueries[section][i]);
                beginQueries[section][i] = 0;
                endQueries[section][i] = 0;
            }
            pending[section] = 0;
            depth[section] = 0;
        }
        active = false;
    }

    /** State-wide GPU stage averages for the benchmark JSON. */
    public static java.util.Map<String, Double> snapshotStages() {
        java.util.Map<String, Double> result = new java.util.LinkedHashMap<>();
        String[] names = {"frame", "world", "upscale", "shadowDrawn", "composite",
                "final", "terrain", "sky", "hud", "presentGpu"};
        int[] order = {FRAME, WORLD, UPSCALE, SHADOW, COMPOSITE, FINAL, TERRAIN, SKY, HUD, PRESENT};
        for (int i = 0; i < order.length; i++) {
            int section = order[i];
            result.put(names[i], totalSamples[section] == 0
                    ? 0.0 : totalMs[section] / totalSamples[section]);
        }
        return result;
    }

    /**
     * Nested stage cost per world frame. Several of these sections are recorded more than once
     * per frame (terrain layers, composite passes), so their event average cannot be compared
     * with the world total directly.
     */
    public static java.util.Map<String, Double> snapshotPerFrameStages() {
        java.util.Map<String, Double> result = new java.util.LinkedHashMap<>();
        int frames = totalSamples[WORLD];
        String[] names = {"terrain", "sky", "shadow", "composite", "final"};
        int[] order = {TERRAIN, SKY, SHADOW, COMPOSITE, FINAL};
        for (int i = 0; i < order.length; i++) {
            result.put(names[i], frames == 0 ? 0.0 : totalMs[order[i]] / frames);
        }
        return result;
    }
}
