package net.cr512.modernopt.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.cr512.modernopt.config.ModernOptConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Lightweight, configurable in-game performance HUD and text logger. */
public final class PerformanceOverlay {
    private static final Logger LOGGER = LoggerFactory.getLogger("ModernOPT");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static long previousFrameAt;
    private static double frameTimeMs;
    private static long lastLogAt;
    private static boolean logErrorReported;
    private static final ArrayDeque<Double> FRAME_HISTORY = new ArrayDeque<>();

    private PerformanceOverlay() {}

    public static void render(GuiGraphics graphics) {
        if (!ModernOptConfig.ENABLED.get()) {
            if (BenchmarkRecorder.isRecording()) BenchmarkRecorder.stop();
            previousFrameAt = 0L;
            frameTimeMs = 0.0;
            FRAME_HISTORY.clear();
            return;
        }
        if (!ModernOptConfig.OVERLAY_ENABLED.get() && !ModernOptConfig.LOGGING_ENABLED.get()
            && !BenchmarkRecorder.isRecording()) return;
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.nanoTime();
        if (previousFrameAt != 0L) {
            frameTimeMs = (now - previousFrameAt) / 1_000_000.0;
            if (frameTimeMs > 0 && Double.isFinite(frameTimeMs)) {
                FRAME_HISTORY.addLast(frameTimeMs);
                while (FRAME_HISTORY.size() > 240) FRAME_HISTORY.removeFirst();
            }
        }
        previousFrameAt = now;
        BenchmarkRecorder.sample(now, minecraft, frameTimeMs);

        if (ModernOptConfig.OVERLAY_ENABLED.get() && minecraft.level != null
            && !minecraft.getDebugOverlay().showDebugScreen()) {
            List<String> lines = new ArrayList<>();
            if (ModernOptConfig.OVERLAY_SHOW_FPS.get()) lines.add("FPS: " + minecraft.getFps());
            if (ModernOptConfig.OVERLAY_SHOW_CPU.get()) {
                OperatingSystemMXBean os = operatingSystem();
                if (os != null) lines.add("CPU process/system: " + percent(os.getProcessCpuLoad()) + " / " + percent(os.getCpuLoad()));
            }
            if (ModernOptConfig.OVERLAY_SHOW_FRAME_TIME.get() && frameTimeMs > 0) {
                lines.add(String.format(Locale.ROOT, "Frame time: %.2f ms", frameTimeMs));
            }
            if (ModernOptConfig.OVERLAY_SHOW_ONE_PERCENT_LOW.get() && !FRAME_HISTORY.isEmpty()) {
                double[] times = FRAME_HISTORY.stream().mapToDouble(Double::doubleValue).sorted().toArray();
                int slowCount = Math.max(1, (int) Math.ceil(times.length * 0.01));
                double slowAverage = 0;
                for (int i = times.length - slowCount; i < times.length; i++) slowAverage += times[i];
                slowAverage /= slowCount;
                lines.add(String.format(Locale.ROOT, "1%% low: %.0f FPS", slowAverage > 0 ? 1000.0 / slowAverage : 0));
            }
            if (ModernOptConfig.OVERLAY_SHOW_P95_FRAME_TIME.get() && FRAME_HISTORY.size() >= 10) {
                double[] times = FRAME_HISTORY.stream().mapToDouble(Double::doubleValue).sorted().toArray();
                double p95 = times[Math.min(times.length - 1, (int) Math.ceil(times.length * 0.95) - 1)];
                lines.add(String.format(Locale.ROOT, "Frame time p95: %.2f ms", p95));
            }
            if (BenchmarkRecorder.isRecording()) lines.add("BENCHMARK: recording " + BenchmarkRecorder.status());
            if (ModernOptConfig.OVERLAY_SHOW_MEMORY.get()) {
                Runtime runtime = Runtime.getRuntime();
                long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
                long allocated = runtime.totalMemory() / (1024L * 1024L);
                long maximum = runtime.maxMemory() / (1024L * 1024L);
                lines.add("Java heap: " + used + " / " + allocated + " MB (max " + maximum + ")");
            }
            if (ModernOptConfig.OVERLAY_SHOW_RENDER_INFO.get()) {
                int scale = ModernOptConfig.RENDER_SCALE_PERCENT.get();
                String upscaler = switch (ModernOptConfig.UPSCALER_MODE.get()) {
                    case 0 -> "None";
                    case 1 -> "FSR 1";
                    case 2 -> "Bilinear";
                    case 3 -> "Nearest";
                    case 4 -> "SoftScale";
                    default -> "Unknown";
                };
                lines.add("World: " + scale + "% · " + upscaler);
                lines.add("Particle culling: " + (ModernOptConfig.PARTICLE_CULLING_ENABLED.get()
                    ? ModernOptConfig.PARTICLE_RENDER_DISTANCE.get() + " blocks" : "off"));
            }
            drawLines(graphics, minecraft, lines);
        }
        if (ModernOptConfig.LOGGING_ENABLED.get()) writeSample(now, minecraft);
    }

    private static OperatingSystemMXBean operatingSystem() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof OperatingSystemMXBean os ? os : null;
    }

    private static String percent(double load) {
        return load < 0 ? "n/a" : Math.round(load * 100.0) + "%";
    }

    private static void drawLines(GuiGraphics graphics, Minecraft minecraft, List<String> lines) {
        if (lines.isEmpty()) return;
        int corner = ModernOptConfig.OVERLAY_POSITION.get();
        int margin = 6;
        int lineHeight = 11;
        int maxWidth = 0;
        for (String line : lines) maxWidth = Math.max(maxWidth, minecraft.font.width(line));
        int x = (corner == 1 || corner == 3) ? minecraft.getWindow().getGuiScaledWidth() - maxWidth - margin : margin;
        int y = (corner == 2 || corner == 3)
            ? minecraft.getWindow().getGuiScaledHeight() - lines.size() * lineHeight - margin : margin;
        for (String line : lines) {
            int alpha = Math.max(0, Math.min(255, Math.round(ModernOptConfig.OVERLAY_BACKGROUND_OPACITY.get() * 2.55f)));
            graphics.fill(x - 3, y - 2, x + maxWidth + 3, y + 9, (alpha << 24));
            graphics.drawString(minecraft.font, Component.literal(line), x, y, 0xFFE8F2FF, false);
            y += lineHeight;
        }
    }

    private static void writeSample(long now, Minecraft minecraft) {
        long interval = ModernOptConfig.LOG_INTERVAL_SECONDS.get() * 1_000_000_000L;
        if (lastLogAt != 0L && now - lastLogAt < interval) return;
        lastLogAt = now;
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        String line = String.format(Locale.ROOT,
            "%s | fps=%d | cpuProcess=%s | cpuSystem=%s | frameTimeMs=%.2f | ramUsedMB=%d | ramAllocatedMB=%d | ramMaxMB=%d | enabled=%s | renderScale=%d%% | upscaler=%d | particleCulling=%s | particleDistance=%d%n",
            LocalDateTime.now().format(TIME), minecraft.getFps(),
            operatingSystem() == null ? "n/a" : percent(operatingSystem().getProcessCpuLoad()),
            operatingSystem() == null ? "n/a" : percent(operatingSystem().getCpuLoad()), frameTimeMs, used,
            runtime.totalMemory() / (1024L * 1024L), runtime.maxMemory() / (1024L * 1024L),
            ModernOptConfig.ENABLED.get(), ModernOptConfig.RENDER_SCALE_PERCENT.get(),
            ModernOptConfig.UPSCALER_MODE.get(), ModernOptConfig.PARTICLE_CULLING_ENABLED.get(),
            ModernOptConfig.PARTICLE_RENDER_DISTANCE.get());
        try {
            Path file = FMLPaths.CONFIGDIR.get().resolve("modernopt-performance.txt");
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            logErrorReported = false;
        } catch (IOException exception) {
            if (!logErrorReported) {
                LOGGER.error("Could not append ModernOPT performance log.", exception);
                logErrorReported = true;
            }
        }
    }
}
