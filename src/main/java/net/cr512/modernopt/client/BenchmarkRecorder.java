package net.cr512.modernopt.client;

import net.cr512.modernopt.config.ModernOptConfig;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Low-overhead frame-time recorder. Writes a CSV to config/modernopt-benchmarks
 * when stopped. Measurements are client-side frame intervals, not GPU timestamps.
 */
public final class BenchmarkRecorder {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final List<Double> FRAME_TIMES = new ArrayList<>();
    private static BufferedWriter writer;
    private static Path outputFile;
    private static long startedAt;
    private static long frames;
    private static long lastFlushAt;
    private static double lastFrameTimeMs;
    private static String lastResult = "Not run";
    private static String lastError = "";

    private BenchmarkRecorder() {}

    public static boolean isRecording() {
        return writer != null;
    }

    public static String status() {
        if (isRecording()) return "Recording · " + frames + " frames";
        return lastError.isEmpty() ? lastResult : "Error: " + lastError;
    }

    public static Path outputFile() {
        return outputFile;
    }

    public static void start() {
        if (isRecording()) return;
        try {
            Path directory = FMLPaths.CONFIGDIR.get().resolve("modernopt-benchmarks");
            Files.createDirectories(directory);
            outputFile = directory.resolve("benchmark-" + LocalDateTime.now().format(FILE_TIME) + ".csv");
            writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            writer.write("elapsed_ms,frame_time_ms,fps,heap_used_mb,heap_allocated_mb,render_scale_pct,upscaler,modernopt_enabled,shader_pack_reported\n");
            writer.flush();
            startedAt = System.nanoTime();
            lastFlushAt = startedAt;
            frames = 0;
            lastFrameTimeMs = 0;
            FRAME_TIMES.clear();
            lastError = "";
            lastResult = "Recording";
        } catch (IOException exception) {
            writer = null;
            lastError = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
    }

    /** Called once per HUD render; does not allocate a row object per frame. */
    public static void sample(long now, Minecraft minecraft, double frameTimeMs) {
        if (!isRecording()) return;
        lastFrameTimeMs = frameTimeMs;
        if (frameTimeMs <= 0 || !Double.isFinite(frameTimeMs)) return;
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long allocatedMb = runtime.totalMemory() / (1024L * 1024L);
        try {
            writer.write(String.format(Locale.ROOT, "%d,%.4f,%d,%d,%d,%d,%d,%s,%s%n",
                (now - startedAt) / 1_000_000L, frameTimeMs, minecraft.getFps(), usedMb, allocatedMb,
                ModernOptConfig.RENDER_SCALE_PERCENT.get(), ModernOptConfig.UPSCALER_MODE.get(),
                ModernOptConfig.ENABLED.get(), "unknown"));
            frames++;
            FRAME_TIMES.add(frameTimeMs);
            // Flush periodically rather than forcing disk I/O every frame.
            if (now - lastFlushAt >= 2_000_000_000L) {
                writer.flush();
                lastFlushAt = now;
            }
        } catch (IOException exception) {
            lastError = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            stop();
        }
    }

    public static void stop() {
        if (writer == null) return;
        try {
            writer.flush();
            writer.close();
            writer = null;
            double average = FRAME_TIMES.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double fps = average > 0 ? 1000.0 / average : 0;
            List<Double> sorted = new ArrayList<>(FRAME_TIMES);
            Collections.sort(sorted);
            double p95 = percentile(sorted, 0.95);
            double p99 = percentile(sorted, 0.99);
            lastResult = String.format(Locale.ROOT, "%d frames · avg %.1f FPS · p95 %.2f ms · p99 %.2f ms",
                frames, fps, p95, p99);
            lastError = "";
        } catch (IOException exception) {
            writer = null;
            lastError = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
    }

    private static double percentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        int index = Math.min(sorted.size() - 1, Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1));
        return sorted.get(index);
    }
}
