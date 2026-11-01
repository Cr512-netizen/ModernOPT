package net.cr512.modernopt;

import net.cr512.modernopt.config.ModernOptSettings;
import net.cr512.modernopt.render.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Logs FPS statistics to a file in the config folder every second.
 * Provides: avg FPS, ms/frame, worst frame, 1% low, 0.1% low, and mod settings.
 */
@EventBusSubscriber(modid = ModernOPT.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class FpsLogger {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static BufferedWriter writer;
    private static Path logFile;
    private static long lastLogTime = 0;
    private static final List<Long> frameTimesNanos = new ArrayList<>(4096);
    private static long lastFrameNanos = 0;
    private static boolean wasEnabled = false;

    private FpsLogger() {
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Post event) {
        onFrame();
    }

    /**
     * Called every frame from RenderFrameEvent.Post to collect frame times.
     */
    public static void onFrame() {
        if (!ModernOptSettings.logFps()) {
            closeWriter();
            return;
        }

        long now = System.nanoTime();
        if (lastFrameNanos > 0) {
            long frameTime = now - lastFrameNanos;
            if (frameTime > 0 && frameTime < 1_000_000_000L) {
                frameTimesNanos.add(frameTime);
            }
        }
        lastFrameNanos = now;

        // Log every second
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastLogTime >= 1000) {
            logStats(nowMs);
            lastLogTime = nowMs;
        }
    }

    private static void logStats(long timestampMs) {
        if (frameTimesNanos.isEmpty()) {
            return;
        }

        // Ensure writer is open
        if (writer == null) {
            openWriter();
            if (writer == null) {
                return;
            }
        }

        try {
            // Calculate statistics
            List<Long> sorted = new ArrayList<>(frameTimesNanos);
            Collections.sort(sorted);

            long sum = 0;
            for (long v : sorted) {
                sum += v;
            }

            double avgMs = sum / 1_000_000.0 / sorted.size();
            double avgFps = 1000.0 / avgMs;
            double minMs = sorted.get(0) / 1_000_000.0;
            double maxMs = sorted.get(sorted.size() - 1) / 1_000_000.0;

            int p1Index = Math.min(sorted.size() - 1, sorted.size() / 100);
            double p1Ms = sorted.get(p1Index) / 1_000_000.0;
            double p1Fps = 1000.0 / p1Ms;

            int p01Index = Math.min(sorted.size() - 1, sorted.size() / 1000);
            double p01Ms = sorted.get(p01Index) / 1_000_000.0;
            double p01Fps = 1000.0 / p01Ms;

            // Get current mod settings
            int renderScale = ModernOptSettings.renderScalePercent();
            String upscaler = RenderPipeline.activeUpscalerLabel();
            int msaa = RenderPipeline.activeSamples();
            int internalW = RenderPipeline.internalWidth();
            int internalH = RenderPipeline.internalHeight();
            int windowW = Minecraft.getInstance().getWindow().getWidth();
            int windowH = Minecraft.getInstance().getWindow().getHeight();

            // Write CSV line
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            writer.write(String.format("%s,%.1f,%.2f,%.2f,%.2f,%.1f,%.2f,%.1f,%d,%s,%dx%d@%dx%d MSAAx%d,%d samples%n",
                    timestamp,
                    avgFps,
                    avgMs,
                    minMs,
                    maxMs,
                    p1Fps,
                    p1Ms,
                    p01Fps,
                    renderScale,
                    upscaler,
                    windowW, windowH, internalW, internalH,
                    msaa,
                    sorted.size()
            ));
            writer.flush();

            // Clear for next second
            frameTimesNanos.clear();

        } catch (IOException e) {
            ModernOPT.LOG.error("[ModernOPT] Failed to write FPS log", e);
            closeWriter();
        }
    }

    private static void openWriter() {
        try {
            Path configDir = FMLPaths.CONFIGDIR.get().resolve("modernopt");
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }

            String fileName = "fps_log_" + LocalDateTime.now().format(FILE_DATE_FORMAT) + ".csv";
            logFile = configDir.resolve(fileName);

            writer = new BufferedWriter(new FileWriter(logFile.toFile(), true));

            // Write header if file is new
            if (Files.size(logFile) == 0) {
                writer.write("timestamp,avg_fps,avg_ms,min_ms,max_ms,p1_fps,p1_ms,p01_fps,render_scale,upscaler,window_res,internal_res,msaa,samples\n");
                writer.flush();
            }

            ModernOPT.LOG.info("[ModernOPT] FPS logging started: {}", logFile);

        } catch (IOException e) {
            ModernOPT.LOG.error("[ModernOPT] Failed to open FPS log file", e);
            closeWriter();
        }
    }

    private static void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
                ModernOPT.LOG.info("[ModernOPT] FPS logging stopped: {}", logFile);
            } catch (IOException e) {
                ModernOPT.LOG.error("[ModernOPT] Failed to close FPS log file", e);
            }
            writer = null;
            logFile = null;
        }
    }

    /**
     * Call when the setting is toggled off or mod is shutting down.
     */
    public static void shutdown() {
        // Log any remaining frames
        if (!frameTimesNanos.isEmpty()) {
            logStats(System.currentTimeMillis());
        }
        closeWriter();
        frameTimesNanos.clear();
        lastFrameNanos = 0;
        lastLogTime = 0;
    }
}