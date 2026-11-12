package net.cr512.modernopt.render;

import net.cr512.modernopt.ModernOPT;
import org.lwjgl.opengl.GL33C;

/**
 * Lightweight GPU timer for the two phases of a ModernOPT frame: the Iris/vanilla world
 * render at the internal resolution and ModernOPT's own upscale passes. Uses
 * {@code GL_TIME_ELAPSED} queries with a two-deep ring so polling never stalls the GPU.
 * Diagnostic only; costs a couple of query objects and no synchronisation.
 */
public final class GpuProfiler {

    public static final int WORLD = 0;
    public static final int UPSCALE = 1;
    private static final int SECTIONS = 2;
    private static final int RING = 3;

    private static final int[][] queries = new int[SECTIONS][RING];
    private static final int[] pending = new int[SECTIONS];
    private static final double[] accumMs = new double[SECTIONS];
    private static final int[] samples = new int[SECTIONS];
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
                queries[section][i] = GL33C.glGenQueries();
            }
        }
        active = true;
    }

    public static void begin(int section) {
        ensureQueries();
        int slot = pending[section] % RING;
        GL33C.glBeginQuery(GL33C.GL_TIME_ELAPSED, queries[section][slot]);
    }

    public static void end(int section) {
        if (!active) {
            return;
        }
        GL33C.glEndQuery(GL33C.GL_TIME_ELAPSED);
        if (pending[section] >= RING - 1) {
            // Poll the query started two frames ago: it has surely finished, so this never blocks.
            int poll = (pending[section] - (RING - 1) + RING) % RING;
            long nanos = GL33C.glGetQueryObjectui64(queries[section][poll], GL33C.GL_QUERY_RESULT);
            accumMs[section] += nanos / 1_000_000.0;
            samples[section]++;
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
        long now = System.nanoTime();
        if (now - lastLogNanos < 2_000_000_000L) {
            return;
        }
        lastLogNanos = now;
        if (!active) {
            return;
        }
        double world = samples[WORLD] == 0 ? 0 : accumMs[WORLD] / samples[WORLD];
        double upscale = samples[UPSCALE] == 0 ? 0 : accumMs[UPSCALE] / samples[UPSCALE];
        ModernOPT.LOG.info("[BENCH][GPU] world {} ms ({}), upscale {} ms ({}), samples {}",
                String.format("%.2f", world), samples[WORLD],
                String.format("%.2f", upscale), samples[UPSCALE], samples[UPSCALE]);
        ModernOPT.LOG.info("[BENCH][CPU] worldCpu {} ms ({})",
                String.format("%.2f", cpuWorldSamples == 0 ? 0 : cpuWorldAccumMs / cpuWorldSamples),
                cpuWorldSamples);
        cpuWorldAccumMs = 0;
        cpuWorldSamples = 0;
        accumMs[WORLD] = 0;
        accumMs[UPSCALE] = 0;
        samples[WORLD] = 0;
        samples[UPSCALE] = 0;
    }

    public static void reset() {
        accumMs[WORLD] = 0;
        accumMs[UPSCALE] = 0;
        samples[WORLD] = 0;
        samples[UPSCALE] = 0;
        lastLogNanos = System.nanoTime();
    }
}
