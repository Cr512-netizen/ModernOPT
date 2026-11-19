package net.cr512.modernopt.render;

import net.cr512.modernopt.ModernOPT;

/**
 * Bench-only split of the world CPU time: how much is terrain layer submission, and how often the
 * render-target/viewport hooks run. It exists to compare ModernOPT's per-frame plumbing with a
 * plain main-target swap (VIRA/RenderScale) without guessing.
 */
public final class WorldCpuProfiler {

    private static final boolean ENABLED = Boolean.getBoolean("modernopt.bench");
    private static long terrainStart;
    private static double terrainMs;
    private static int terrainSamples;
    private static long entityStart;
    private static double entityMs;
    private static int entitySamples;
    private static long blockEntityStart;
    private static double blockEntityMs;
    private static int blockEntitySamples;
    private static int bindCalls;
    private static int viewportCalls;
    private static int framebufferBinds;
    private static int clampRepairs;
    private static int clampGlQueries;
    private static int frames;
    private static long lastLog = System.nanoTime();

    private WorldCpuProfiler() {
    }

    public static void beginTerrain() {
        if (ENABLED) {
            terrainStart = System.nanoTime();
        }
    }

    public static void endTerrain() {
        if (ENABLED && terrainStart != 0L) {
            terrainMs += (System.nanoTime() - terrainStart) / 1_000_000.0;
            terrainSamples++;
            terrainStart = 0L;
        }
    }

    public static void beginEntity() {
        if (ENABLED) {
            entityStart = System.nanoTime();
        }
    }

    public static void endEntity() {
        if (ENABLED && entityStart != 0L) {
            entityMs += (System.nanoTime() - entityStart) / 1_000_000.0;
            entitySamples++;
            entityStart = 0L;
        }
    }

    public static void beginBlockEntity() {
        if (ENABLED) {
            blockEntityStart = System.nanoTime();
        }
    }

    public static void endBlockEntity() {
        if (ENABLED && blockEntityStart != 0L) {
            blockEntityMs += (System.nanoTime() - blockEntityStart) / 1_000_000.0;
            blockEntitySamples++;
            blockEntityStart = 0L;
        }
    }

    public static void noteBind() {
        if (ENABLED) {
            bindCalls++;
        }
    }

    public static void noteWorldFrame() {
        if (ENABLED) {
            frames++;
        }
    }

    public static void noteViewport() {
        if (ENABLED) {
            viewportCalls++;
        }
    }

    public static void noteFramebufferBind() {
        if (ENABLED) {
            framebufferBinds++;
        }
    }

    public static void noteClampRepair() {
        if (ENABLED) {
            clampRepairs++;
        }
    }

    public static void noteClampGlQuery() {
        if (ENABLED) {
            clampGlQueries++;
        }
    }

    public static void maybeLog() {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastLog < 2_000_000_000L) {
            return;
        }
        lastLog = now;
        if (terrainSamples == 0 && entitySamples == 0 && viewportCalls == 0) {
            return;
        }
        ModernOPT.LOG.info("[BENCH][WCPU] per frame: terrain {} ms ({}), entities {} ms ({}), blockEntities {} ms ({}), "
                        + "bindCalls {}, fbBinds {}, viewport {}, clampRepairs {}, clampQueries {} ({} frames)",
                perFrame(terrainMs), terrainSamples,
                perFrame(entityMs), entitySamples,
                perFrame(blockEntityMs), blockEntitySamples,
                perFrame(bindCalls), perFrame(framebufferBinds), perFrame(viewportCalls),
                perFrame(clampRepairs), clampGlQueries, frames);
        terrainMs = 0;
        terrainSamples = 0;
        entityMs = 0;
        entitySamples = 0;
        blockEntityMs = 0;
        blockEntitySamples = 0;
        bindCalls = 0;
        viewportCalls = 0;
        framebufferBinds = 0;
        clampRepairs = 0;
        clampGlQueries = 0;
        frames = 0;
    }

    private static String perFrame(int count) {
        return String.format("%.2f", frames == 0 ? 0.0 : (double) count / frames);
    }

    private static String perFrame(double totalMs) {
        return String.format("%.2f", frames == 0 ? 0.0 : totalMs / frames);
    }
}
