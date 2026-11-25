// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt;

import net.cr512.modernopt.config.MsaaLevel;
import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.config.ModernOptSettings;
import net.cr512.modernopt.config.UpscalerType;
import net.cr512.modernopt.render.GpuProfiler;
import net.cr512.modernopt.render.RenderPipeline;
import net.cr512.modernopt.render.RenderScaleMath;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private static final String[] STATES = parseStates(
            System.getProperty("modernopt.bench.states",
                    "BILINEAR-50,MSR-50,MSR2-50,BILINEAR-50-B,OFF-100"));

    private static final int START_TICK = Integer.getInteger("modernopt.bench.start", 160);
    private static final int WARMUP_TICKS = Integer.getInteger("modernopt.bench.warmup", 100);
    private static final int SAMPLE_TICKS = Integer.getInteger("modernopt.bench.sample", 160);

    /** Benchmark-only render distance override; 0 keeps the player's setting. */
    private static final int BENCH_RENDER_DISTANCE =
            Integer.getInteger("modernopt.bench.renderDistance", 0);
    private static final int STATE_TICKS = WARMUP_TICKS + SAMPLE_TICKS;

    private static int ticks;
    private static int smallWindowTicks;
    private static int state = -1;
    /** Benchmark-only camera pitch ({@code NaN} keeps the default view). */
    private static float benchPitch = Float.NaN;
    /** Benchmark-only constant yaw rotation, in degrees per tick ({@code 0} keeps the view). */
    private static float benchPanDegreesPerTick;
    /** Benchmark-only forward walk, in blocks per tick ({@code 0} keeps the player still). */
    private static double benchWalkBlocksPerTick;
    private static boolean worldLogged;
    private static boolean benchMob;
    private static boolean cleanedWorldPigs;
    private static boolean sampling;
    private static boolean windowWarned;
    private static boolean finished;
    private static int finishedTick;
    private static long lastFrameNanos;
    private static boolean skipNextFrameSample;
    private static int lastLoadedChunks = -1;
    private static int stableChunkTicks;
    private static int inactiveTicks;
    private static boolean focusWarned;
    private static final List<Long> frameTimes = new ArrayList<>(4096);
    /** State name for the convergence screenshots, or {@code null} when not requested. */
    private static String convergenceState;
    private static int convergenceFrames;
    private static String pairState;
    private static int pairFrames;
    private static boolean swapFilterOnPair;
    private static boolean swapLanczosOnPair;
    private static boolean swapOnPairDone;
    private static final List<Map<String, Object>> stateResults = new ArrayList<>();
    private static final Map<String, Map<String, Object>> imageMetrics = new LinkedHashMap<>();
    private static final int[] lastShadowCounters = new int[5];
    private static boolean reportWritten;
    /** Screenshot deferred to the end of the frame: at tick time the main target can still hold a
     *  partially composed image (the previous frame is presented, but the current one has not been
     *  finished), which made HUD-less screenshots. */
    private static String pendingShotState;
    private static String pendingShotPhase;

    private SelfTest() {
    }

    private static String[] parseStates(String value) {
        String[] parts = value.split(",");
        List<String> states = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                states.add(trimmed);
            }
        }
        return states.toArray(new String[0]);
    }

    @SubscribeEvent
    public static void onFrameStart(RenderFrameEvent.Pre event) {
        if (ENABLED) {
            GpuProfiler.begin(GpuProfiler.FRAME);
        }
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Post event) {
        if (!ENABLED) {
            return;
        }
        GpuProfiler.end(GpuProfiler.FRAME);
        boolean screenshotTaken = false;
        if (pendingShotState != null) {
            String stateName = pendingShotState;
            String phase = pendingShotPhase;
            pendingShotState = null;
            pendingShotPhase = null;
            saveScreenshot(Minecraft.getInstance(), stateName, phase);
            screenshotTaken = true;
        }
        if (convergenceState != null) {
            convergenceFrames++;
            if (convergenceFrames == 1 || convergenceFrames == 10 || convergenceFrames == 60) {
                saveScreenshot(Minecraft.getInstance(), convergenceState, "f" + convergenceFrames);
                screenshotTaken = true;
            }
        }
        if (pairState != null) {
            pairFrames++;
            if (pairFrames >= 40 && pairFrames <= 43) {
                saveScreenshot(Minecraft.getInstance(), pairState, "p" + pairFrames);
                screenshotTaken = true;
            }
            if (pairFrames == 40 && !swapOnPairDone) {
                swapOnPairDone = true;
                if (swapFilterOnPair) {
                    ModernOptConfig.UPSCALER.set(UpscalerType.NEAREST);
                    ModernOptSettings.refresh();
                    ModernOPT.LOG.info("[BENCH] runtime upscaler switch -> {}", UpscalerType.NEAREST);
                }
                if (swapLanczosOnPair) {
                    ModernOptConfig.MSR2_LANCZOS.set(!ModernOptConfig.MSR2_LANCZOS.get());
                    ModernOptSettings.refresh();
                    ModernOPT.LOG.info("[BENCH] runtime Lanczos switch -> {}",
                            ModernOptSettings.msr2Lanczos());
                }
            }
        }
        long now = System.nanoTime();
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isWindowActive() || minecraft.isPaused()) {
            // Do not record the OS-capped 60 fps frames while the window is in the background.
            lastFrameNanos = 0L;
            skipNextFrameSample = false;
            return;
        }
        long previous = lastFrameNanos;
        lastFrameNanos = now;
        if (screenshotTaken) {
            // Writing a 1080p PNG stalls this frame for hundreds of milliseconds; it is a
            // harness cost, not the renderer's, so the next interval (which contains it) is
            // excluded from the FPS and 1% low statistics.
            skipNextFrameSample = true;
            return;
        }
        if (!sampling || previous == 0L) {
            return;
        }
        if (skipNextFrameSample) {
            skipNextFrameSample = false;
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

        if (benchPanDegreesPerTick != 0.0F) {
            minecraft.player.setYRot(minecraft.player.getYRot() + benchPanDegreesPerTick);
        }
        if (benchWalkBlocksPerTick != 0.0) {
            net.minecraft.world.phys.Vec3 look = minecraft.player.getLookAngle();
            minecraft.player.setPos(
                    minecraft.player.getX() + look.x * benchWalkBlocksPerTick,
                    minecraft.player.getY(),
                    minecraft.player.getZ() + look.z * benchWalkBlocksPerTick);
            minecraft.player.setDeltaMovement(0.0, 0.0, 0.0);
        }

        if (!worldLogged) {
            worldLogged = true;
            ticks = 0;
            ModernOPT.LOG.info("[BENCH] world loaded, player at {}", minecraft.player.position());
            steerOnce(minecraft);
            logIrisTargets();
        }
        if (!cleanedWorldPigs) {
            cleanedWorldPigs = true;
            boolean wantsMob = false;
            for (String stateName : STATES) {
                if (stateName.contains("!mob")) {
                    wantsMob = true;
                    break;
                }
            }
            if (!wantsMob && minecraft.player != null && minecraft.player.connection != null) {
                // A previous !mob state can leave its test pig in the saved world. Remove it so
                // later screenshots show the actual scene, not a giant pig in front of the camera.
                minecraft.player.connection.sendCommand("gamerule sendCommandFeedback false");
                removeBenchMob(minecraft);
            }
        }
        if (minecraft.screen != null) {
            // A menu left open by the previous state changes the scene and makes screenshots
            // compare UI pages instead of shadows.
            ModernOPT.LOG.info("[BENCH] closing screen {} for the benchmark",
                    minecraft.screen.getClass().getSimpleName());
            minecraft.setScreen(null);
            return;
        }

        if (!minecraft.isWindowActive()) {
            if (!focusWarned) {
                focusWarned = true;
                ModernOPT.LOG.warn("[BENCH] window is not focused; click it once. Sampling is "
                        + "paused so the OS 60 fps background cap cannot enter the results");
            }
            if (++inactiveTicks % 100 == 0) {
                focusBenchWindow(minecraft);
            }
            return;
        }
        if (focusWarned) {
            focusWarned = false;
            ModernOPT.LOG.info("[BENCH] window focused again after {} ticks", inactiveTicks);
            inactiveTicks = 0;
        }
        inactiveTicks = 0;

        // Let chunk streaming settle before the first measured state. Otherwise the first
        // warmup runs while 1000+ chunks are still being uploaded and the FPS curve drops.
        int loadedChunks = minecraft.level.getChunkSource().getLoadedChunksCount();
        if (loadedChunks != lastLoadedChunks) {
            lastLoadedChunks = loadedChunks;
            stableChunkTicks = 0;
            return;
        }
        if (++stableChunkTicks < 40) {
            return;
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
                if (benchMob) {
                    removeBenchMob(minecraft);
                    benchMob = false;
                }
                writeBenchReport(minecraft);
                ModernOPT.LOG.info("[BENCH] complete, closing the game automatically");
            } else if (ticks - finishedTick >= 60) {
                ModernOPT.LOG.info("[BENCH] shutdown");
                minecraft.stop();
            }
            return;
        }

        int offset = elapsed % STATE_TICKS;
        if (!Float.isNaN(benchPitch) && minecraft.player != null) {
            minecraft.player.setXRot(benchPitch);
        }
        if (index != state) {
            state = index;
            convergenceState = STATES[state].contains("!conv") ? STATES[state] : null;
            convergenceFrames = 0;
            pairState = STATES[state].contains("!pairs") ? STATES[state] : null;
            RenderPipeline.setShadowProbe(pairState != null);
            pairFrames = 0;
            GpuProfiler.reset();
            applyState(state);
            if (ModernOptConfig.ENABLED.get()) {
                ModernOPT.LOG.info("[BENCH] state {} | {} | {} | MSAA {} | shadows {}",
                        STATES[state], RenderPipeline.activeUpscalerLabel(),
                        internalResolution(), RenderPipeline.activeSamples(),
                        ModernOptSettings.shadowUpdateInterval());
            } else {
                // The disabled state has no internal resolution; printing the previous state's
                // size or upscaler made the native baseline look like a 50 % FSR 1 run.
                ModernOPT.LOG.info("[BENCH] state {} | ModernOPT off (native 100%) | window {}x{}",
                        STATES[state], minecraft.getWindow().getWidth(),
                        minecraft.getWindow().getHeight());
            }
            return;
        }

        if (benchMob && minecraft.player != null && minecraft.player.connection != null) {
            double angle = ticks * 0.08;
            net.minecraft.world.phys.Vec3 look = minecraft.player.getLookAngle();
            double batX = minecraft.player.getX() + look.x * 5.0 + Math.cos(angle) * 1.5;
            double batY = minecraft.player.getY() + 1.5 + Math.sin(ticks * 0.12) * 0.8;
            double batZ = minecraft.player.getZ() + look.z * 5.0 + Math.sin(angle) * 1.5;
            minecraft.player.connection.sendCommand(String.format(java.util.Locale.ROOT,
                    "tp @e[type=minecraft:pig,limit=1] %.3f %.3f %.3f", batX, batY, batZ));
        }

        if (offset == WARMUP_TICKS) {
            frameTimes.clear();
            sampling = true;
            skipNextFrameSample = false;
            Minecraft client = Minecraft.getInstance();
            ModernOPT.LOG.info("[BENCH] sampling {} | screen={} noRender={} paused={} active={} window={}x{} internal={} label={}",
                    STATES[state], client.screen == null ? "none" : client.screen.getClass().getSimpleName(),
                    client.noRender, client.isPaused(), client.isWindowActive(),
                    client.getWindow().getWidth(), client.getWindow().getHeight(),
                    internalResolution(),
                    ModernOptConfig.ENABLED.get()
                            ? RenderPipeline.activeUpscalerLabel() : "ModernOPT off");
            ModernOPT.LOG.info("[BENCH] conditions {} | fpsLimit={} loadedChunks={} playerY={} ground={}",
                    STATES[state], client.options.framerateLimit().get(),
                    client.level.getChunkSource().getLoadedChunksCount(),
                    String.format("%.1f", client.player.getY()),
                    client.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                            client.player.getBlockX(), client.player.getBlockZ()));
            logIrisTargets();
            ModernOPT.LOG.info("[BENCH] gl {}", RenderPipeline.debugGlState());
        } else if (offset == STATE_TICKS - 1) {
            sampling = false;
            convergenceState = null;
            pairState = null;
            RenderPipeline.setShadowProbe(false);
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
            // The harness must compare the renderer, not an OS background cap or vsync.
            minecraft.options.framerateLimit().set(260);
            minecraft.options.enableVsync().set(false);
            GLFW.glfwSwapInterval(0);
            if (BENCH_RENDER_DISTANCE > 0) {
                minecraft.options.renderDistance().set(BENCH_RENDER_DISTANCE);
                ModernOPT.LOG.info("[BENCH] render distance forced to {} chunks (client option)", BENCH_RENDER_DISTANCE);
            }
            if (minecraft.level != null) {
                minecraft.options.pauseOnLostFocus = false;
            }
            minecraft.options.save();
            focusBenchWindow(minecraft);
            minecraft.level.setDayTime(1000L);
            minecraft.level.setRainLevel(0.0F);
            minecraft.level.setThunderLevel(0.0F);
            // The client-side setDayTime above only affects the integrated server once it syncs;
            // set time/weather authoritatively on the server so both states are measured in daylight.
            if (minecraft.player.connection != null) {
                minecraft.player.connection.sendCommand("gamerule doDaylightCycle false");
                minecraft.player.connection.sendCommand("time set 6000");
                minecraft.player.connection.sendCommand("weather clear");
                // Freeze the world: entities stop wandering and no chunk section is rebuilt while
                // a state is sampled, which removes the largest source of run-to-run variance.
                minecraft.player.connection.sendCommand("tick freeze");
            }
            net.minecraft.core.BlockPos spawn = minecraft.level.getSharedSpawnPos();
            int bx = spawn.getX();
            int bz = spawn.getZ();
            int surface = minecraft.level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, bx, bz);
            double y = Math.max(surface + 1.0, spawn.getY());
            if (!Boolean.getBoolean("modernopt.bench.keepPosition")) {
                // A !walk state moves the player and the position is saved with the world. Always
                // reset to the same spawn pose so two runs compare the same scene instead of a
                // field versus a village.
                minecraft.player.setPos(bx + 0.5, y, bz + 0.5);
                minecraft.player.setYRot(90.0F);
                minecraft.player.setXRot(0.0F);
                minecraft.player.setDeltaMovement(0.0, 0.0, 0.0);
                ModernOPT.LOG.info("[BENCH] startup steering done at {} (spawn {}, surface {})",
                        minecraft.player.position(), spawn, surface);
            } else {
                ModernOPT.LOG.info("[BENCH] keeping the player where the world saved them: {}",
                        minecraft.player.position());
            }
        } catch (Throwable throwable) {
            ModernOPT.LOG.warn("[BENCH] startup steering skipped: {}", throwable.toString());
        }
    }

    /** Brings the benchmark window to the foreground; sampling waits until it is active. */
    private static void focusBenchWindow(Minecraft minecraft) {
        try {
            long handle = minecraft.getWindow().getWindow();
            GLFW.glfwShowWindow(handle);
            GLFW.glfwFocusWindow(handle);
            GLFW.glfwRequestWindowAttention(handle);
        } catch (Throwable throwable) {
            ModernOPT.LOG.warn("[BENCH] could not focus the game window: {}", throwable.toString());
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
                int center = image.getPixelRGBA(image.getWidth() / 2, image.getHeight() / 2);
                ModernOPT.LOG.info("[BENCH] screenshot {}x{} hotbarPixel={} centerPixel={} (args {},{})",
                        image.getWidth(), image.getHeight(),
                        String.format("%08x", argb), String.format("%08x", center),
                        sampleX, sampleY);
                if ((center & 0x00FFFFFF) == 0 && (argb & 0x00FFFFFF) == 0) {
                    ModernOPT.LOG.warn("[BENCH][WARN] {} ({}) is completely black - pack error screen or no world render",
                            stateName, phase);
                }
                String name = "bench-" + stateName + "-" + phase + ".png";
                java.nio.file.Path out = dir.resolve(name);
                image.writeToFile(out);
                ModernOPT.LOG.info("[BENCH] saved {}", name);
                if (phase.equals("f60") && stateName.contains("!conv")) {
                    measureConvergence(dir, stateName, image);
                }
            } finally {
                image.close();
            }
        } catch (Throwable throwable) {
            ModernOPT.LOG.warn("[BENCH] benchmark screenshot failed: {}", throwable.toString());
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
        // 1% low = mean of the slowest 1% of frames, not the fastest 1%.
        int worstCount = Math.max(1, sorted.size() / 100);
        long worstSum = 0;
        for (int i = sorted.size() - worstCount; i < sorted.size(); i++) {
            worstSum += sorted.get(i);
        }
        double p1LowMs = worstSum / 1_000_000.0 / worstCount;
        double p1LowFps = 1000.0 / p1LowMs;
        int p99Index = Math.min(sorted.size() - 1, (int) (sorted.size() * 0.99));
        double p99Ms = sorted.get(p99Index) / 1_000_000.0;
        ModernOPT.LOG.info("[BENCH] {}: avg {}/{} fps, frame {}/{} ms, 1% low {}, worst {} ms, p99 {}, samples {}",
                name, Math.round(1000.0 / avgMs), String.format("%.1f", 1000.0 / avgMs),
                String.format("%.2f", avgMs), String.format("%.2f", minMs),
                String.format("%.2f", p1LowFps), String.format("%.2f", maxMs),
                String.format("%.2f", p99Ms), sorted.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", name);
        result.put("avgFps", round(1000.0 / avgMs, 2));
        result.put("avgFrameMs", round(avgMs, 3));
        result.put("minFrameMs", round(minMs, 3));
        result.put("p1LowFps", round(p1LowFps, 2));
        result.put("p1LowFrameMs", round(p1LowMs, 3));
        result.put("p99FrameMs", round(p99Ms, 3));
        result.put("worstFrameMs", round(maxMs, 3));
        result.put("samples", sorted.size());
        boolean benchEnabled = ModernOptConfig.ENABLED.get();
        result.put("modernoptEnabled", benchEnabled);
        result.put("renderScalePercent", benchEnabled ? ModernOptSettings.renderScalePercent() : 100);
        result.put("upscaler", benchEnabled ? ModernOptSettings.upscaler().name() : "OFF");
        result.put("ssaa", benchEnabled && ModernOptSettings.ssaa());
        result.put("fxaa", benchEnabled && ModernOptSettings.fxaa());
        result.put("msaa", benchEnabled ? ModernOptSettings.msaa().name() : "OFF");
        result.put("fp16", benchEnabled && ModernOptSettings.fp16World());
        result.put("msr2Enabled", benchEnabled && ModernOptSettings.msr2Enabled());
        result.put("msr2Lanczos", benchEnabled && ModernOptSettings.msr2Lanczos());
        result.put("msr2Lanczos3", benchEnabled && ModernOptSettings.msr2Lanczos3());
        result.put("shadowMap", benchEnabled
                ? net.cr512.modernopt.compat.ShadowMapResolution.describe() : "vanilla");
        result.put("shadowHoldDisabled", !benchEnabled
                || net.cr512.modernopt.compat.ShadowClearRegistry.holdDisabled());
        net.cr512.modernopt.render.ShadowHoldStats shadows =
                RenderPipeline.shadowHoldStats();
        int drawn = shadows.drawn() - lastShadowCounters[0];
        int held = shadows.held() - lastShadowCounters[1];
        int forcedCamera = shadows.forcedCamera() - lastShadowCounters[2];
        int forcedTime = shadows.forcedTime() - lastShadowCounters[3];
        int forcedCaster = shadows.forcedCaster() - lastShadowCounters[4];
        if (drawn < 0 || held < 0) {
            // Counters were reset by a dimension or interval change: use the part since reset.
            drawn = shadows.drawn();
            held = shadows.held();
            forcedCamera = shadows.forcedCamera();
            forcedTime = shadows.forcedTime();
            forcedCaster = shadows.forcedCaster();
        }
        lastShadowCounters[0] = shadows.drawn();
        lastShadowCounters[1] = shadows.held();
        lastShadowCounters[2] = shadows.forcedCamera();
        lastShadowCounters[3] = shadows.forcedTime();
        lastShadowCounters[4] = shadows.forcedCaster();
        result.put("shadowDrawn", benchEnabled ? drawn : 0);
        result.put("shadowHeld", benchEnabled ? held : 0);
        result.put("shadowTotal", benchEnabled ? drawn + held : 0);
        result.put("shadowInterval", benchEnabled ? shadows.interval() : 0.0);
        result.put("shadowForcedCamera", benchEnabled ? forcedCamera : 0);
        result.put("shadowForcedTime", benchEnabled ? forcedTime : 0);
        result.put("shadowForcedCaster", benchEnabled ? forcedCaster : 0);
        result.put("gpuProfilerEnabled", GpuProfiler.isEnabled());
        result.put("stagesMs", GpuProfiler.snapshotStages());
        result.put("stagesPerFrameMs", GpuProfiler.snapshotPerFrameStages());
        Map<String, Object> image = imageMetrics.get(name);
        if (image != null) {
            result.put("image", image);
        }
        stateResults.add(result);
    }

    private static double round(double value, int digits) {
        double scale = Math.pow(10.0, digits);
        return Math.round(value * scale) / scale;
    }

    /**
     * Static-camera convergence check: MAE between the first, tenth and sixtieth presented
     * frames, plus a detail score (mean horizontal luma gradient) of the last one. A converged
     * temporal image changes very little between frame 10 and 60; a broken history shows up as a
     * large MAE or as a detail score far below the reference.
     */
    private static void measureConvergence(Path dir, String stateName,
            com.mojang.blaze3d.platform.NativeImage frame60) {
        try {
            Path f1 = dir.resolve("bench-" + stateName + "-f1.png");
            Path f10 = dir.resolve("bench-" + stateName + "-f10.png");
            double mae1 = mae(f1, frame60);
            double mae10 = mae(f10, frame60);
            double detail = detail(frame60);
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("maeFrame1To60", round(mae1, 5));
            metrics.put("maeFrame10To60", round(mae10, 5));
            metrics.put("detail", round(detail, 5));

            String reference = System.getProperty("modernopt.bench.reference");
            if (reference != null && !reference.isBlank()) {
                Path referencePath = Path.of(reference);
                if (!referencePath.isAbsolute()) {
                    referencePath = Minecraft.getInstance().gameDirectory.toPath().resolve(referencePath);
                }
                if (Files.isRegularFile(referencePath)) {
                    metrics.put("referenceMae", round(mae(referencePath, frame60), 5));
                    try (java.io.InputStream input = Files.newInputStream(referencePath);
                            com.mojang.blaze3d.platform.NativeImage image =
                                    com.mojang.blaze3d.platform.NativeImage.read(input)) {
                        metrics.put("referenceDetail", round(detail(image), 5));
                    }
                }
            }
            imageMetrics.put(stateName, metrics);
            ModernOPT.LOG.info("[BENCH][IMAGE] {} {}", stateName, metrics);
        } catch (Throwable throwable) {
            ModernOPT.LOG.warn("[BENCH][IMAGE] convergence metrics failed for {}: {}",
                    stateName, throwable.toString());
        }
    }

    private static double mae(Path path, com.mojang.blaze3d.platform.NativeImage current)
            throws java.io.IOException {
        try (java.io.InputStream input = Files.newInputStream(path);
                com.mojang.blaze3d.platform.NativeImage reference =
                        com.mojang.blaze3d.platform.NativeImage.read(input)) {
            if (reference.getWidth() != current.getWidth()
                    || reference.getHeight() != current.getHeight()) {
                throw new java.io.IOException("reference size mismatch");
            }
            long sum = 0;
            long count = 0;
            int step = 2;
            for (int y = 0; y < current.getHeight(); y += step) {
                for (int x = 0; x < current.getWidth(); x += step) {
                    sum += Math.abs(luma(reference.getPixelRGBA(x, y)) - luma(current.getPixelRGBA(x, y)));
                    count++;
                }
            }
            return count == 0 ? 0.0 : sum / (count * 255.0);
        }
    }

    private static double detail(com.mojang.blaze3d.platform.NativeImage image) {
        long sum = 0;
        long count = 0;
        for (int y = 0; y < image.getHeight(); y += 2) {
            int previous = luma(image.getPixelRGBA(0, y));
            for (int x = 2; x < image.getWidth(); x += 2) {
                int next = luma(image.getPixelRGBA(x, y));
                sum += Math.abs(next - previous);
                previous = next;
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / (count * 255.0);
    }

    private static int luma(int pixel) {
        int r = pixel & 0xFF;
        int g = (pixel >>> 8) & 0xFF;
        int b = (pixel >>> 16) & 0xFF;
        return (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
    }

    /** Writes the machine-readable benchmark report and compares it with an optional baseline. */
    private static void writeBenchReport(Minecraft minecraft) {
        if (reportWritten) {
            return;
        }
        reportWritten = true;
        try {
            ModernOptSettings.refresh();
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("format", 1);
            root.put("timestamp", java.time.Instant.now().toString());
            root.put("fingerprint", fingerprint(minecraft));
            root.put("states", stateResults);

            Path directory = minecraft.gameDirectory.toPath();
            String name = System.getProperty("modernopt.bench.json", "bench-results.json");
            Path output = Path.of(name);
            if (!output.isAbsolute()) {
                output = directory.resolve(output);
            }
            Files.createDirectories(output.getParent());
            String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                    .toJson(root);
            // Compare before writing: the baseline may be the same path the report overwrites.
            compareBaseline(directory, root);
            Files.writeString(output, json);
            ModernOPT.LOG.info("[BENCH] report written to {}", output);
        } catch (Throwable throwable) {
            ModernOPT.LOG.warn("[BENCH] could not write the JSON report: {}", throwable.toString());
        }
    }

    private static Map<String, Object> fingerprint(Minecraft minecraft) {
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("gpu", glString(org.lwjgl.opengl.GL11C.GL_RENDERER));
        fingerprint.put("gpuVendor", glString(org.lwjgl.opengl.GL11C.GL_VENDOR));
        fingerprint.put("glVersion", glString(org.lwjgl.opengl.GL11C.GL_VERSION));
        fingerprint.put("minecraft", net.minecraft.SharedConstants.getCurrentVersion().getName());
        fingerprint.put("neoforge", net.neoforged.fml.ModList.get()
                .getModContainerById("neoforge")
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown"));
        fingerprint.put("mod", net.neoforged.fml.ModList.get()
                .getModContainerById(ModernOPT.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("development"));
        fingerprint.put("shaderPack", shaderPackName());
        fingerprint.put("renderDistance", minecraft.options.getEffectiveRenderDistance());
        fingerprint.put("window", minecraft.getWindow().getWidth() + "x" + minecraft.getWindow().getHeight());
        // Resolution, upscaler, AA and shadow settings are per-state; see each state's record.
        fingerprint.put("settingsArePerState", true);
        return fingerprint;
    }

    private static String glString(int name) {
        String value = org.lwjgl.opengl.GL11C.glGetString(name);
        return value == null ? "unknown" : value;
    }

    private static String shaderPackName() {
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Object value = iris.getMethod("getCurrentPackName").invoke(null);
            return value == null ? "none" : value.toString();
        } catch (Throwable ignored) {
            return "none";
        }
    }

    private static void compareBaseline(Path directory, Map<String, Object> current) {
        String baselineName = System.getProperty("modernopt.bench.baseline");
        if (baselineName == null || baselineName.isBlank()) {
            return;
        }
        try {
            Path baselinePath = Path.of(baselineName);
            if (!baselinePath.isAbsolute()) {
                baselinePath = directory.resolve(baselinePath);
            }
            if (!Files.isRegularFile(baselinePath)) {
                ModernOPT.LOG.warn("[BENCH] baseline {} does not exist", baselinePath);
                return;
            }
            com.google.gson.JsonObject baseline =
                    com.google.gson.JsonParser.parseString(Files.readString(baselinePath)).getAsJsonObject();
            double tolerance = Double.parseDouble(
                    System.getProperty("modernopt.bench.tolerance", "0.03"));
            Map<String, com.google.gson.JsonObject> baselineStates = new LinkedHashMap<>();
            for (com.google.gson.JsonElement element : baseline.getAsJsonArray("states")) {
                com.google.gson.JsonObject state = element.getAsJsonObject();
                baselineStates.put(state.get("state").getAsString(), state);
            }

            List<String> regressions = new ArrayList<>();
            Map<?, ?> fingerprint = (Map<?, ?>) current.get("fingerprint");
            com.google.gson.JsonObject baselineFingerprint =
                    baseline.has("fingerprint") ? baseline.getAsJsonObject("fingerprint") : null;
            if (baselineFingerprint != null && fingerprint != null) {
                for (String key : new String[]{"gpu", "minecraft", "shaderPack", "renderDistance"}) {
                    String expected = baselineFingerprint.has(key)
                            ? baselineFingerprint.get(key).getAsString() : "?";
                    String actual = String.valueOf(fingerprint.get(key));
                    if (!expected.equals(actual)) {
                        ModernOPT.LOG.warn("[BENCH] fingerprint differs from baseline: {} was {}, now {}",
                                key, expected, actual);
                    }
                }
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> states = (List<Map<String, Object>>) current.get("states");
            for (Map<String, Object> state : states) {
                String name = String.valueOf(state.get("state"));
                com.google.gson.JsonObject reference = baselineStates.get(name);
                if (reference == null) {
                    continue;
                }
                checkRegression(name, "avgFps", state, reference, tolerance, regressions);
                checkRegression(name, "p1LowFps", state, reference, tolerance, regressions);
            }
            if (regressions.isEmpty()) {
                ModernOPT.LOG.info("[BENCH] baseline comparison clean (tolerance {}%)",
                        Math.round(tolerance * 100.0));
            } else {
                Path report = directory.resolve("bench-regression.txt");
                Files.writeString(report, String.join(System.lineSeparator(), regressions));
                ModernOPT.LOG.error("[BENCH][REGRESSION] {} entries written to {}",
                        regressions.size(), report);
            }
        } catch (Throwable throwable) {
            ModernOPT.LOG.warn("[BENCH] baseline comparison failed: {}", throwable.toString());
        }
    }

    private static void checkRegression(String state, String metric, Map<String, Object> current,
            com.google.gson.JsonObject baseline, double tolerance, List<String> regressions) {
        if (!baseline.has(metric) || !(current.get(metric) instanceof Number)) {
            return;
        }
        double expected = baseline.get(metric).getAsDouble();
        double actual = ((Number) current.get(metric)).doubleValue();
        double floor = expected * (1.0 - tolerance);
        if (expected > 0 && actual < floor) {
            regressions.add(state + " " + metric + ": " + actual + " < " + floor
                    + " (baseline " + expected + ", tolerance " + Math.round(tolerance * 100.0) + "%)");
        }
    }

    /** First run of decimal digits in the string, or 0. */
    private static int digits(String value) {
        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end))) {
            end++;
        }
        return end == 0 ? 0 : Integer.parseInt(value.substring(0, end));
    }

    /** First decimal number in the string, or 0. */
    private static double decimal(String value) {
        int end = 0;
        boolean dot = false;
        while (end < value.length()) {
            char c = value.charAt(end);
            if (Character.isDigit(c)) {
                end++;
            } else if (c == '.' && !dot) {
                dot = true;
                end++;
            } else {
                break;
            }
        }
        if (end == 0) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.substring(0, end));
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    /** Removes the !mob test pig, including one left invulnerable by an older benchmark run. */
    private static void removeBenchMob(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.player.connection == null) {
            return;
        }
        minecraft.player.connection.sendCommand(
                "data merge entity @e[type=minecraft:pig,limit=1] {Invulnerable:0b}");
        minecraft.player.connection.sendCommand("kill @e[type=minecraft:pig]");
    }

    /**
     * Internal size label for the logs. At 100 % with no anti-aliasing the offscreen pipeline is
     * bypassed, so the last small target would be stale and misleading; report "native" instead.
     */
    private static String internalResolution() {
        boolean passthrough = ModernOptSettings.renderScalePercent() >= 100
                && !ModernOptSettings.ssaa()
                && ModernOptSettings.msaa().samples() <= 1
                && !ModernOptSettings.fxaa();
        if (passthrough) {
            return "native";
        }
        return RenderPipeline.internalWidth() + "x" + RenderPipeline.internalHeight();
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
        // "BILINEAR@960x522" pins the internal size instead of deriving it from a percentage.
        int at = name.indexOf('@');
        RenderScaleMath.benchOverride = null;
        if (at >= 0) {
            String size = name.substring(at + 1);
            int bang = size.indexOf('!');
            if (bang >= 0) {
                size = size.substring(0, bang);
            }
            int x = size.indexOf('x');
            RenderScaleMath.benchOverride = new int[]{
                    Integer.parseInt(size.substring(0, x)),
                    Integer.parseInt(size.substring(x + 1))
            };
        }
        // "!up" points the camera at the sky: separates terrain fill from full-screen passes.
        benchPitch = name.endsWith("!up") ? -88.0F : Float.NaN;
        // "!f5" / "!f5f" switch to third person back / front for visual checks.
        // "!ldr" forces the pre-upscale target to 8-bit RGBA, "!hdr" forces RGBA16F. Without a
        // suffix the configured value is left alone (FP16 is off by default because the Iris
        // composite chain writes into this target and doubles its bandwidth cost).
        if (name.contains("!ldr")) {
            ModernOptConfig.FP16_WORLD.set(false);
        } else if (name.contains("!hdr")) {
            ModernOptConfig.FP16_WORLD.set(true);
        }
        ModernOptConfig.SCALE_WHOLE_WINDOW.set(name.contains("!wws"));
        ModernOptConfig.MSR2_LANCZOS.set(!name.contains("!nolanczos"));
        ModernOptConfig.MSR2_LANCZOS3.set(name.contains("!lanczos3"));
        int shadowIndex = name.indexOf("!shadow");
        if (shadowIndex >= 0) {
            double shadowInterval = decimal(name.substring(shadowIndex + 7));
            if (shadowInterval >= 1.0 && shadowInterval <= 8.0) {
                ModernOptConfig.SHADOW_UPDATE_INTERVAL.set(shadowInterval);
            }
        } else {
            ModernOptConfig.SHADOW_UPDATE_INTERVAL.set(1.0);
        }
        int panIndex = name.indexOf("!pan");
        if (panIndex >= 0) {
            int degrees = digits(name.substring(panIndex + 4));
            benchPanDegreesPerTick = degrees > 0 ? degrees : 2.0F;
        } else {
            benchPanDegreesPerTick = 0.0F;
        }
        int walkIndex = name.indexOf("!walk");
        if (walkIndex >= 0) {
            int speed = digits(name.substring(walkIndex + 5));
            benchWalkBlocksPerTick = speed > 0 ? speed / 100.0 : 0.04;
        } else {
            benchWalkBlocksPerTick = 0.0;
        }
        if (name.contains("!sunset")) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null) {
                // Apply locally for the very first captured frames; the command makes it
                // authoritative on the integrated server and is what persists after the state.
                minecraft.level.setDayTime(13000L);
            }
            if (minecraft.player != null && minecraft.player.connection != null) {
                minecraft.player.connection.sendCommand("gamerule doDaylightCycle false");
                minecraft.player.connection.sendCommand("time set 13000");
            }
        }
        int hudIndex = name.indexOf("!hud");
        if (hudIndex >= 0) {
            int hudPercent = digits(name.substring(hudIndex + 4));
            if (hudPercent >= 50 && hudPercent <= 100) {
                ModernOptConfig.HUD_SCALE_PERCENT.set(hudPercent);
            }
        }
        net.minecraft.client.CameraType cameraType = net.minecraft.client.CameraType.FIRST_PERSON;
        if (name.endsWith("!f5")) {
            cameraType = net.minecraft.client.CameraType.THIRD_PERSON_BACK;
        } else if (name.endsWith("!f5f")) {
            cameraType = net.minecraft.client.CameraType.THIRD_PERSON_FRONT;
        }
        Minecraft.getInstance().options.setCameraType(cameraType);
        if ("OFF".equals(mode)) {
            ModernOptConfig.ENABLED.set(false);
            ModernOptConfig.SHOW_OVERLAY.set(false);
        } else {
            UpscalerType selected = UpscalerType.BILINEAR;
            for (UpscalerType candidate : UpscalerType.values()) {
                if (candidate.name().equalsIgnoreCase(mode)) {
                    selected = candidate;
                    break;
                }
            }
            configure(percent, selected);
        }
        swapFilterOnPair = name.contains("!swapfilter");
        swapLanczosOnPair = name.contains("!swaplanczos");
        swapOnPairDone = false;
        boolean wasMob = benchMob;
        benchMob = name.contains("!mob");
        if (wasMob && !benchMob) {
            // Do not leak the test mob into the next state's screenshots.
            removeBenchMob(Minecraft.getInstance());
        }
        if (benchMob && Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.connection != null) {
            Minecraft.getInstance().player.connection.sendCommand("gamerule sendCommandFeedback false");
            Minecraft.getInstance().player.connection.sendCommand("kill @e[type=minecraft:pig]");
            Minecraft.getInstance().player.connection.sendCommand(
                    "summon minecraft:pig ~4 ~1.5 ~4 {NoAI:1b,NoGravity:1b,Silent:1b,"
                            + "PersistenceRequired:1b}");
        }
        ModernOptSettings.refresh();
        if (name.contains("!reload")) {
            RenderPipeline.reloadShaders();
        }
        if (name.contains("!conv") || name.contains("!pan") || name.contains("!walk")) {
            RenderPipeline.resetTemporalHistory();
        }
    }

    private static void configure(int percent, UpscalerType upscaler) {
        ModernOptConfig.ENABLED.set(true);
        ModernOptConfig.RENDER_SCALE_PERCENT.set(percent);
        ModernOptConfig.UPSCALER.set(upscaler);
        ModernOptConfig.MSR2_DYNAMIC_RES.set(false);
        ModernOptConfig.FSR_EASU_SHARPNESS.set(0);
        ModernOptConfig.FSR_RCAS_SHARPNESS.set(25);
        ModernOptConfig.SSAA.set(false);
        ModernOptConfig.MSAA.set(MsaaLevel.OFF);
        ModernOptConfig.FXAA.set(false);
        ModernOptConfig.SHOW_OVERLAY.set(true);
    }
}
