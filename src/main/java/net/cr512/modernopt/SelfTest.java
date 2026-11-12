package net.cr512.modernopt;

import net.cr512.modernopt.config.MsaaLevel;
import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.config.ModernOptSettings;
import net.cr512.modernopt.config.UpscalerType;
import net.cr512.modernopt.render.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local benchmark harness: measures frame time per render state.
 *
 * <p>The harness never takes control away from the player. It performs a single one-time
 * placement/look-at when the world first loads and then only observes - no per-tick position,
 * rotation, movement or screen overrides, no focus stealing and no forced client shutdown.</p>
 */
@EventBusSubscriber(modid = ModernOPT.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class SelfTest {

    /**
     * The harness teleports the player, changes world rules, writes screenshots and shuts the
     * game down, so it must never run in a normal installation. Development runs enable it with
     * {@code -Dmodernopt.bench=true} (already set by the Gradle {@code runClient} task).
     */
    private static final boolean ENABLED = Boolean.getBoolean("modernopt.bench");

    private static final String[] STATES = {
            "BILINEAR-50",
            "FSR2-50",
            "MSR2-50",
            "BILINEAR-50-B",
            "OFF-100"
    };

    private static final int START_TICK = 160;
    private static final int WARMUP_TICKS = 100;
    private static final int SAMPLE_TICKS = 160;
    private static final int STATE_TICKS = WARMUP_TICKS + SAMPLE_TICKS;

    private static int ticks;
    private static int smallWindowTicks;
    private static int state = -1;
    private static boolean worldLogged;
    private static boolean sampling;
    private static boolean windowWarned;
    private static boolean finished;
    private static int finishedTick;
    private static long lastFrameNanos;
    private static final List<Long> frameTimes = new ArrayList<>(4096);
    /** Screenshot deferred to the end of the frame: at tick time the main target can still hold a
     *  partially composed image (the previous frame is presented, but the current one has not been
     *  finished), which made HUD-less screenshots. */
    private static String pendingShotState;
    private static String pendingShotPhase;

    private SelfTest() {
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Post event) {
        if (!ENABLED) {
            return;
        }
        if (pendingShotState != null) {
            String stateName = pendingShotState;
            String phase = pendingShotPhase;
            pendingShotState = null;
            pendingShotPhase = null;
            saveScreenshot(Minecraft.getInstance(), stateName, phase);
        }
        long now = System.nanoTime();
        long previous = lastFrameNanos;
        lastFrameNanos = now;
        if (!sampling || previous == 0L) {
            return;
        }
        long frameTime = now - previous;
        if (frameTime > 0 && frameTime < 1_000_000_000L) {
            frameTimes.add(frameTime);
        }
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (!ENABLED) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (minecraft.getWindow().getWidth() < 1280 || minecraft.getWindow().getHeight() < 720) {
            if (++smallWindowTicks > 1200 && !windowWarned) {
                windowWarned = true;
                ModernOPT.LOG.error("[BENCH] window never reached 1280x720 ({}x{}), benchmark disabled",
                        minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
            }
            return;
        }
        smallWindowTicks = 0;

        if (!worldLogged) {
            worldLogged = true;
            ticks = 0;
            ModernOPT.LOG.info("[BENCH] world loaded, player at {}", minecraft.player.position());
            diagEntityScan(minecraft);
            steerOnce(minecraft);
            logIrisTargets();
        }

        ticks++;
        int elapsed = ticks - START_TICK;
        if (elapsed < 0) {
            return;
        }

        int index = elapsed / STATE_TICKS;
        if (index >= STATES.length) {
            // Benchmark is done: hand back control briefly, then close the game automatically.
            sampling = false;
            if (!finished) {
                finished = true;
                finishedTick = ticks;
                ModernOPT.LOG.info("[BENCH] complete, closing the game automatically");
            } else if (ticks - finishedTick >= 60) {
                ModernOPT.LOG.info("[BENCH] shutdown");
                minecraft.stop();
            }
            return;
        }

        int offset = elapsed % STATE_TICKS;
        if (index != state) {
            state = index;
            applyState(state);
            ModernOPT.LOG.info("[BENCH] state {} | {} | {}x{} | MSAA {}",
                    STATES[state], RenderPipeline.activeUpscalerLabel(),
                    RenderPipeline.internalWidth(), RenderPipeline.internalHeight(),
                    RenderPipeline.activeSamples());
            return;
        }

        if (offset == WARMUP_TICKS) {
            frameTimes.clear();
            sampling = true;
            Minecraft client = Minecraft.getInstance();
            ModernOPT.LOG.info("[BENCH] sampling {} | screen={} noRender={} paused={} active={} window={}x{} internal={}x{} label={}",
                    STATES[state], client.screen == null ? "none" : client.screen.getClass().getSimpleName(),
                    client.noRender, client.isPaused(), client.isWindowActive(),
                    client.getWindow().getWidth(), client.getWindow().getHeight(),
                    RenderPipeline.internalWidth(), RenderPipeline.internalHeight(),
                    RenderPipeline.activeUpscalerLabel());
            ModernOPT.LOG.info("[BENCH] conditions {} | fpsLimit={} loadedChunks={} playerY={} ground={}",
                    STATES[state], client.options.framerateLimit().get(),
                    client.level.getChunkSource().getLoadedChunksCount(),
                    String.format("%.1f", client.player.getY()),
                    client.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                            client.player.getBlockX(), client.player.getBlockZ()));
            logIrisTargets();
            ModernOPT.LOG.info("[BENCH] gl {}", RenderPipeline.debugGlState());
            pendingShotState = STATES[state];
            pendingShotPhase = "start";
        } else if (offset == STATE_TICKS - 1) {
            sampling = false;
            logStats(STATES[state]);
            pendingShotState = STATES[state];
            pendingShotPhase = "end";
        }
    }

    /**
     * Puts the player on the world spawn point once per session, standing on the ground and
     * facing a fixed direction. Never in the air (heightmap-snapped), so the benchmark view is
     * the same on every run and both upscalers are measured under identical conditions.
     */
    private static void steerOnce(Minecraft minecraft) {
        try {
            minecraft.level.setDayTime(1000L);
            minecraft.level.setRainLevel(0.0F);
            minecraft.level.setThunderLevel(0.0F);
            // The client-side setDayTime above only affects the integrated server once it syncs;
            // set time/weather authoritatively on the server so both states are measured in daylight.
            if (minecraft.player.connection != null) {
                minecraft.player.connection.sendCommand("gamerule doDaylightCycle false");
                minecraft.player.connection.sendCommand("time set 6000");
                minecraft.player.connection.sendCommand("weather clear");
            }
            net.minecraft.core.BlockPos spawn = minecraft.level.getSharedSpawnPos();
            int bx = spawn.getX();
            int bz = spawn.getZ();
            int surface = minecraft.level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, bx, bz);
            double y = Math.max(surface + 1.0, spawn.getY());
            minecraft.player.setPos(bx + 0.5, y, bz + 0.5);
            minecraft.player.setYRot(90.0F);
            minecraft.player.setXRot(0.0F);
            minecraft.player.setDeltaMovement(0.0, 0.0, 0.0);
            ModernOPT.LOG.info("[BENCH] startup steering done at {} (spawn {}, surface {}) - player now has full control",
                    minecraft.player.position(), spawn, surface);
        } catch (Throwable throwable) {
            ModernOPT.LOG.warn("[BENCH] startup steering skipped: {}", throwable.toString());
        }
    }

    private static void logIrisTargets() {
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Object manager = iris.getMethod("getPipelineManager").invoke(null);
            Object pipeline = manager.getClass().getMethod("getPipelineNullable").invoke(manager);
            if (pipeline == null) {
                ModernOPT.LOG.info("[BENCH] iris pipeline: none");
                return;
            }
            Field mainField = pipeline.getClass().getDeclaredField("renderTargets");
            mainField.setAccessible(true);
            Object main = mainField.get(pipeline);
            Method mainWidth = main.getClass().getMethod("getCurrentWidth");
            Method mainHeight = main.getClass().getMethod("getCurrentHeight");

            Field shadowField = pipeline.getClass().getDeclaredField("shadowRenderTargets");
            shadowField.setAccessible(true);
            Object shadow = shadowField.get(pipeline);
            Object shadowResolution = shadow == null ? "none"
                    : shadow.getClass().getMethod("getResolution").invoke(shadow);

            Field targetsField = main.getClass().getDeclaredField("targets");
            targetsField.setAccessible(true);
            Object[] targets = (Object[]) targetsField.get(main);
            StringBuilder sizes = new StringBuilder();
            for (int i = 0; i < targets.length; i++) {
                if (targets[i] == null) {
                    continue;
                }
                Method getWidth = targets[i].getClass().getMethod("getWidth");
                Method getHeight = targets[i].getClass().getMethod("getHeight");
                sizes.append(i).append('=').append(getWidth.invoke(targets[i]))
                        .append('x').append(getHeight.invoke(targets[i])).append(' ');
            }

            ModernOPT.LOG.info("[BENCH] iris targets: main={}x{} shadow={} sizes={}",
                    mainWidth.invoke(main), mainHeight.invoke(main), shadowResolution, sizes);
        } catch (Throwable throwable) {
            ModernOPT.LOG.info("[BENCH] iris target probe failed: {}", throwable.toString());
        }
    }

    /** Saves a benchmark screenshot for visual inspection (two per state: start and end). */
    private static void saveScreenshot(Minecraft minecraft, String stateName, String phase) {
        try {
            java.nio.file.Path dir = minecraft.gameDirectory.toPath().resolve("screenshots");
            java.nio.file.Files.createDirectories(dir);
            com.mojang.blaze3d.pipeline.RenderTarget target = minecraft.getMainRenderTarget();
            com.mojang.blaze3d.platform.NativeImage image =
                    net.minecraft.client.Screenshot.takeScreenshot(target);
            try {
                int sampleX = image.getWidth() / 2;
                int sampleY = image.getHeight() - 24;
                int argb = image.getPixelRGBA(sampleX, sampleY);
                ModernOPT.LOG.info("[BENCH] screenshot {}x{} hotbarPixel={} (args {})",
                        image.getWidth(), image.getHeight(),
                        String.format("%08x", argb), sampleX + "," + sampleY);
                String name = "bench-" + stateName + "-" + phase + ".png";
                java.nio.file.Path out = dir.resolve(name);
                image.writeToFile(out);
                ModernOPT.LOG.info("[BENCH] saved {}", name);
            } finally {
                image.close();
            }
        } catch (Throwable throwable) {
            ModernOPT.LOG.warn("[BENCH] benchmark screenshot failed: {}", throwable.toString());
        }
    }

    /**
     * TEMPORARY DIAGNOSTIC for the "visually flying entities" report: dumps every loaded
     * entity's server-truth position and the terrain height beneath it. Remove once the
     * cause (world data vs render pipeline) is confirmed.
     */
    private static void diagEntityScan(Minecraft minecraft) {
        try {
            int counted = 0;
            for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering()) {
                double x = entity.getX();
                double y = entity.getY();
                double z = entity.getZ();
                int bx = (int) Math.floor(x);
                int bz = (int) Math.floor(z);
                int surfaceY = minecraft.level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, bx, bz);
                double airGap = y - surfaceY;
                ModernOPT.LOG.info("[BENCH][ENTDIAG] {} id={} at ({}, {}, {}), ground={}, airGap={}{}, camera at {}",
                        entity.getType().toShortString(), entity.getId(),
                        String.format("%.1f", x), String.format("%.1f", y), String.format("%.1f", z),
                        surfaceY, String.format("%.1f", airGap),
                        airGap > 1.5 ? "  <-- in the air" : "",
                        minecraft.gameRenderer.getMainCamera().getPosition().toString());
                counted++;
                if (counted > 40) {
                    ModernOPT.LOG.info("[BENCH][ENTDIAG] ...more entities omitted");
                    break;
                }
            }
            ModernOPT.LOG.info("[BENCH][ENTDIAG] scanned {} loaded entities", counted);
        } catch (Throwable throwable) {
            ModernOPT.LOG.warn("[BENCH][ENTDIAG] scan failed: {}", throwable.toString());
        }
    }

    private static void logStats(String name) {
        if (frameTimes.isEmpty()) {
            ModernOPT.LOG.info("[BENCH] {}: no frames sampled", name);
            return;
        }
        List<Long> sorted = new ArrayList<>(frameTimes);
        Collections.sort(sorted);
        long sum = 0;
        for (long value : sorted) {
            sum += value;
        }
        double avgMs = sum / 1_000_000.0 / sorted.size();
        double minMs = sorted.get(0) / 1_000_000.0;
        double maxMs = sorted.get(sorted.size() - 1) / 1_000_000.0;
        double p1Ms = sorted.get(Math.min(sorted.size() - 1, sorted.size() / 100)) / 1_000_000.0;
        ModernOPT.LOG.info("[BENCH] {}: avg {}/{} fps, frame {}/{} ms, p1 {}, samples {}",
                name, Math.round(1000.0 / avgMs), String.format("%.1f", 1000.0 / avgMs),
                String.format("%.2f", avgMs), String.format("%.2f", minMs),
                String.format("%.2f", p1Ms), sorted.size());
    }

    private static void applyState(int index) {
        String name = STATES[index];
        int dash = name.indexOf('-');
        String mode = dash < 0 ? name : name.substring(0, dash);
        int percent = 100;
        if (dash >= 0) {
            int end = dash + 1;
            while (end < name.length() && Character.isDigit(name.charAt(end))) {
                end++;
            }
            percent = Integer.parseInt(name.substring(dash + 1, end));
        }
        switch (mode) {
            case "OFF" -> {
                ModernOptConfig.ENABLED.set(false);
                ModernOptConfig.SHOW_OVERLAY.set(false);
            }
            case "MSR", "MSR2" -> configure(percent, UpscalerType.MSR, true);
            case "FSR2" -> configure(percent, UpscalerType.FSR2, false);
            case "FSR1" -> configure(percent, UpscalerType.FSR1, false);
            default -> configure(percent, UpscalerType.BILINEAR, false);
        }
        ModernOptSettings.refresh();
    }

    private static void configure(int percent, UpscalerType upscaler, boolean msrEnabled) {
        ModernOptConfig.ENABLED.set(true);
        ModernOptConfig.RENDER_SCALE_PERCENT.set(percent);
        ModernOptConfig.UPSCALER.set(upscaler);
        ModernOptConfig.MSR_ENABLED.set(msrEnabled);
        ModernOptConfig.MSR_DYNAMIC_RES.set(false);
        ModernOptConfig.FSR_EASU_SHARPNESS.set(0);
        ModernOptConfig.FSR_RCAS_SHARPNESS.set(25);
        ModernOptConfig.SSAA.set(false);
        ModernOptConfig.MSAA.set(MsaaLevel.OFF);
        ModernOptConfig.FXAA.set(false);
        ModernOptConfig.SHOW_OVERLAY.set(true);
    }
}
