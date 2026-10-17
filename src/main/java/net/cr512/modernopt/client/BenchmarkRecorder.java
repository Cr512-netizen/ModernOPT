package net.cr512.modernopt.client;

import com.sun.management.OperatingSystemMXBean;
import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.render.RenderDiagnostics;
import net.cr512.modernopt.render.RenderScaleController;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
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
 * Writes low-overhead frame and render-path telemetry to config/modernopt-benchmarks.
 * All render timings are CPU wall-clock measurements; this recorder does not claim
 * to measure GPU execution time.
 */
public final class BenchmarkRecorder {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final List<Double> FRAME_TIMES = new ArrayList<>();
    private static final List<Double> WORLD_CPU_TIMES = new ArrayList<>();
    private static final List<Double> POST_CPU_TIMES = new ArrayList<>();
    private static final List<Double> PROCESS_CPU_LOADS = new ArrayList<>();
    private static BufferedWriter writer;
    private static Path outputFile;
    private static long startedAt;
    private static long frames;
    private static long observedFrames;
    private static long lastFlushAt;
    private static String lastResult = "Not run";
    private static String lastError = "";

    private BenchmarkRecorder() {}

    public static boolean isRecording() { return writer != null; }

    public static String status() {
        if (isRecording()) return "Recording · " + frames + " frames";
        return lastError.isEmpty() ? lastResult : "Error: " + lastError;
    }

    public static Path outputFile() { return outputFile; }

    public static void start() {
        if (isRecording()) return;
        try {
            Path directory = FMLPaths.CONFIGDIR.get().resolve("modernopt-benchmarks");
            Files.createDirectories(directory);
            outputFile = directory.resolve("benchmark-" + LocalDateTime.now().format(FILE_TIME) + ".csv");
            writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            writer.write("elapsed_ms,frame_time_ms,fps,heap_used_mb,heap_allocated_mb,cpu_process_pct,cpu_system_pct,window_width,window_height,world_width,world_height,world_pixel_ratio_pct,render_scale_config_pct,render_scale_effective_pct,upscaler,modernopt_enabled,iris_shader_pack_in_use,world_pass_cpu_ms,upscale_post_cpu_ms,gpu_world_ms,gpu_post_ms,main_target_restored,upscaler_path,auxiliary_targets\n");
            writer.flush();
            startedAt = System.nanoTime();
            lastFlushAt = startedAt;
            frames = 0;
            observedFrames = 0;
            FRAME_TIMES.clear();
            WORLD_CPU_TIMES.clear();
            POST_CPU_TIMES.clear();
            PROCESS_CPU_LOADS.clear();
            lastError = "";
            lastResult = "Recording";
        } catch (IOException exception) {
            writer = null;
            lastError = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
    }

    /** Called once per HUD render. */
    public static void sample(long now, Minecraft minecraft, double frameTimeMs) {
        if (!isRecording()) return;
        observedFrames++;
        long elapsedNanos = now - startedAt;
        int durationSeconds = ModernOptConfig.BENCHMARK_DURATION_SECONDS.get();
        if (durationSeconds > 0 && elapsedNanos >= durationSeconds * 1_000_000_000L) {
            stop();
            return;
        }
        int sampleInterval = ModernOptConfig.DIAGNOSTICS_SAMPLE_INTERVAL_FRAMES.get();
        if (observedFrames % Math.max(1, sampleInterval) != 0) return;
        if (frameTimeMs <= 0 || !Double.isFinite(frameTimeMs)) return;
        Runtime runtime = Runtime.getRuntime();
        long usedMb = ModernOptConfig.BENCHMARK_CAPTURE_MEMORY.get()
            ? (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L) : -1;
        long allocatedMb = ModernOptConfig.BENCHMARK_CAPTURE_MEMORY.get()
            ? runtime.totalMemory() / (1024L * 1024L) : -1;
        OperatingSystemMXBean os = ModernOptConfig.BENCHMARK_CAPTURE_CPU.get() ? operatingSystem() : null;
        double processCpu = os == null ? -1 : os.getProcessCpuLoad() * 100.0;
        double systemCpu = os == null ? -1 : os.getCpuLoad() * 100.0;

        int windowWidth = Math.max(1, RenderDiagnostics.lastWindowWidth());
        int windowHeight = Math.max(1, RenderDiagnostics.lastWindowHeight());
        if (windowWidth <= 1 || windowHeight <= 1) {
            windowWidth = minecraft.getWindow().getWidth();
            windowHeight = minecraft.getWindow().getHeight();
        }
        int worldWidth = RenderDiagnostics.lastWorldWidth() > 0
            ? RenderDiagnostics.lastWorldWidth() : windowWidth;
        int worldHeight = RenderDiagnostics.lastWorldHeight() > 0
            ? RenderDiagnostics.lastWorldHeight() : windowHeight;
        double pixelRatio = (double) worldWidth * worldHeight
            / Math.max(1.0, (double) windowWidth * windowHeight) * 100.0;
        double worldCpuMs = RenderDiagnostics.lastFrameCpuMs();
        double postCpuMs = RenderDiagnostics.lastPostCpuMs();

        try {
            writer.write(String.format(Locale.ROOT,
                "%d,%.4f,%d,%d,%d,%.2f,%.2f,%d,%d,%d,%d,%.2f,%d,%d,%d,%s,%s,%.4f,%.4f,%.4f,%.4f,%s,%s,%s%n",
                (now - startedAt) / 1_000_000L, frameTimeMs, minecraft.getFps(), usedMb, allocatedMb,
                processCpu, systemCpu, windowWidth, windowHeight, worldWidth, worldHeight, pixelRatio,
                ModernOptConfig.RENDER_SCALE_PERCENT.get(), RenderScaleController.effectivePercent(),
                RenderDiagnostics.lastUpscalerMode(), ModernOptConfig.ENABLED.get(),
                RenderDiagnostics.lastShaderPackInUse(), worldCpuMs, postCpuMs,
                RenderDiagnostics.lastGpuWorldMs(), RenderDiagnostics.lastGpuPostMs(),
                RenderDiagnostics.lastMainTargetRestored(), csvEscape(RenderDiagnostics.lastUpscalerPath()),
                csvEscape(RenderDiagnostics.lastAuxiliaryTargets())));
            frames++;
            FRAME_TIMES.add(frameTimeMs);
            if (worldCpuMs > 0 && Double.isFinite(worldCpuMs)) WORLD_CPU_TIMES.add(worldCpuMs);
            if (postCpuMs >= 0 && Double.isFinite(postCpuMs)) POST_CPU_TIMES.add(postCpuMs);
            if (processCpu >= 0 && Double.isFinite(processCpu)) PROCESS_CPU_LOADS.add(processCpu);
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
            double average = average(FRAME_TIMES);
            double fps = average > 0 ? 1000.0 / average : 0;
            List<Double> sorted = new ArrayList<>(FRAME_TIMES);
            Collections.sort(sorted);
            double p95 = percentile(sorted, 0.95);
            double p99 = percentile(sorted, 0.99);
            int slowCount = sorted.isEmpty() ? 0 : Math.max(1, (int) Math.ceil(sorted.size() * 0.01));
            double slowAverage = 0;
            for (int i = Math.max(0, sorted.size() - slowCount); i < sorted.size(); i++) slowAverage += sorted.get(i);
            double onePercentLow = slowCount > 0 && slowAverage > 0 ? 1000.0 / (slowAverage / slowCount) : 0;
            long spikes = sorted.stream().filter(value -> value > Math.max(10.0, (average > 0 ? average * 2.0 : 50.0))).count();
            lastResult = String.format(Locale.ROOT,
                "%d frames · avg %.1f FPS · 1%% low %.1f · p95/p99 %.2f/%.2f ms · spikes %d · world CPU %.2f ms · post CPU %.2f ms · process CPU %.1f%% · CSV %s",
                frames, fps, onePercentLow, p95, p99, spikes,
                average(WORLD_CPU_TIMES), average(POST_CPU_TIMES), average(PROCESS_CPU_LOADS),
                outputFile == null ? "unknown" : outputFile.getFileName());
            lastError = "";
        } catch (IOException exception) {
            writer = null;
            lastError = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
    }

    private static String csvEscape(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static OperatingSystemMXBean operatingSystem() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof OperatingSystemMXBean os ? os : null;
    }

    private static double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static double percentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        int index = Math.min(sorted.size() - 1, Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1));
        return sorted.get(index);
    }
}
