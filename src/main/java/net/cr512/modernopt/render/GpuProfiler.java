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
 * <p>Diagnostic only; costs a few query objects and no synchronisation.</p>
 */
public final class GpuProfiler {

    public static final int WORLD = 0;
    public static final int UPSCALE = 1;
    public static final int SHADOW = 2;
    public static final int COMPOSITE = 3;
    public static final int FINAL = 4;
    public static final int TERRAIN = 5;
    public static final int SKY = 6;
    private static final int SECTIONS = 7;
    private static final int RING = 3;

    /**
     * Timers are a development aid: they cost GL queries every frame, so they are only created
     * when the benchmark harness is enabled with {@code -Dmodernopt.bench=true}.
     */
    private static final boolean ENABLED = Boolean.getBoolean("modernopt.bench");

    private static final int[][] beginQueries = new int[SECTIONS][RING];
    private static final int[][] endQueries = new int[SECTIONS][RING];
    private static final int[] pending = new int[SECTIONS];
    private static final double[] accumMs = new double[SECTIONS];
    private static final int[] samples = new int[SECTIONS];
    private static final int[] depth = new int[SECTIONS];
    private static boolean active;
    private static long lastLogNanos = System.nanoTime();

    /** TEMPORARY DIAGNOSTIC: CPU time spent submitting the world section. */
    private static long cpuWorldStart;
    private static double cpuWorldAccumMs;
    private static int cpuWorldSamples;

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
                accumMs[section] += (stop - start) / 1_000_000.0;
                samples[section]++;
            }
        }
        pending[section]++;
    }

    /** TEMPORARY DIAGNOSTIC: marks the start of the world section on the CPU timeline. */
    public static void beginCpuWorld() {
        cpuWorldStart = System.nanoTime();
    }

    /** TEMPORARY DIAGNOSTIC: accumulates the CPU time spent submitting the world section. */
    public static void endCpuWorld() {
        if (cpuWorldStart != 0) {
            cpuWorldAccumMs += (System.nanoTime() - cpuWorldStart) / 1_000_000.0;
            cpuWorldSamples++;
            cpuWorldStart = 0;
        }
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
        ModernOPT.LOG.info("[BENCH][GPU] world {} ms ({}), terrain {} ms ({}), sky {} ms ({}), shadow {} ms ({}), composite {} ms ({}), final {} ms ({}), upscale {} ms ({}), samples {}",
                average(WORLD), samples[WORLD],
                average(TERRAIN), samples[TERRAIN],
                average(SKY), samples[SKY],
                average(SHADOW), samples[SHADOW],
                average(COMPOSITE), samples[COMPOSITE],
                average(FINAL), samples[FINAL],
                average(UPSCALE), samples[UPSCALE], samples[UPSCALE]);
        ModernOPT.LOG.info("[BENCH][CPU] worldCpu {} ms ({})",
                String.format("%.2f", cpuWorldSamples == 0 ? 0 : cpuWorldAccumMs / cpuWorldSamples),
                cpuWorldSamples);
        cpuWorldAccumMs = 0;
        cpuWorldSamples = 0;
        for (int section = 0; section < SECTIONS; section++) {
            accumMs[section] = 0;
            samples[section] = 0;
        }
    }

    private static String average(int section) {
        return String.format("%.2f", samples[section] == 0 ? 0 : accumMs[section] / samples[section]);
    }

    public static void reset() {
        for (int section = 0; section < SECTIONS; section++) {
            accumMs[section] = 0;
            samples[section] = 0;
        }
        lastLogNanos = System.nanoTime();
    }
}
