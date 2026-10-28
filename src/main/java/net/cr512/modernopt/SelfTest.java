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

/** Temporary local benchmark: fixed camera, fixed time, frame-time stats per render state. */
@EventBusSubscriber(modid = ModernOPT.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class SelfTest {

    private static final String[] STATES = {
            "00-current"
    };

    private static final int START_TICK = 400;
    private static final int WARMUP_TICKS = 100;
    private static final int SAMPLE_TICKS = 200;
    private static final int STATE_TICKS = WARMUP_TICKS + SAMPLE_TICKS;

    private static int ticks;
    private static int smallWindowTicks;
    private static int state = -1;
    private static boolean worldLogged;
    private static boolean sampling;
    private static long lastFrameNanos;
    private static final List<Long> frameTimes = new ArrayList<>(4096);

    private SelfTest() {
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Post event) {
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
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (minecraft.getWindow().getWidth() < 1280 || minecraft.getWindow().getHeight() < 720) {
            if (++smallWindowTicks > 1200) {
                ModernOPT.LOG.error("[BENCH] window never reached 1280x720 ({}x{}), aborting",
                        minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
                minecraft.stop();
            }
            return;
        }
        smallWindowTicks = 0;

        if (!worldLogged) {
            worldLogged = true;
            ticks = 0;
            ModernOPT.LOG.info("[BENCH] world loaded, player at {}", minecraft.player.position());
            logIrisTargets();
        }

        ticks++;
        minecraft.level.setDayTime(1000L);
        minecraft.player.setPos(300.5, 63.0, 300.5);
        minecraft.player.setYRot(90.0F);
        minecraft.player.setXRot(0.0F);
        minecraft.player.setDeltaMovement(0.0, 0.0, 0.0);
        minecraft.options.pauseOnLostFocus = false;
        if (minecraft.screen != null) {
            minecraft.setScreen(null);
        }

        int elapsed = ticks - START_TICK;
        if (elapsed < 0) {
            return;
        }

        int index = elapsed / STATE_TICKS;
        if (index >= STATES.length) {
            ModernOPT.LOG.info("[BENCH] complete, stopping client");
            minecraft.stop();
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
            logIrisTargets();
        } else if (offset == STATE_TICKS - 1) {
            sampling = false;
            logStats(STATES[state]);
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
        // Single-state run: measure exactly what the config file requested.
        ModernOptSettings.refresh();
    }

    private static void configure(boolean enabled, int percent, UpscalerType upscaler, MsaaLevel msaa) {
        ModernOptConfig.ENABLED.set(enabled);
        ModernOptConfig.RENDER_SCALE_PERCENT.set(percent);
        ModernOptConfig.UPSCALER.set(upscaler);
        ModernOptConfig.FSR_EASU_SHARPNESS.set(0);
        ModernOptConfig.FSR_RCAS_SHARPNESS.set(25);
        ModernOptConfig.SSAA.set(false);
        ModernOptConfig.MSAA.set(msaa);
        ModernOptConfig.FXAA.set(false);
        ModernOptConfig.SHOW_OVERLAY.set(false);
    }
}
