package net.cr512.modernopt.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A repeatable, local single-player spectator camera pass. It records frame pacing
 * while sweeping through several view angles and flying a short route through the
 * currently loaded surroundings. It never changes ModernOPT settings automatically.
 */
public final class ModernOptBenchmarkScreen extends Screen {
    private static final int WARMUP_TICKS = 60;
    private static final int TICKS_PER_SEGMENT = 60;
    private static final int SEGMENTS = 8;
    private static final double SPEED_PER_TICK = 1.0;
    private static final float[] PITCHES = {-8F, -24F, 4F, -14F, 12F, -30F, 0F, -18F};

    private final Screen parent;
    private final Minecraft minecraft = Minecraft.getInstance();
    private final List<Double> frameTimesMs = new ArrayList<>();
    private final List<String> csvRows = new ArrayList<>();
    private final double startX;
    private final double startY;
    private final double startZ;
    private final float startYaw;
    private final float startPitch;
    private long lastFrameNanos;
    private long startNanos;
    private int elapsedTicks;
    private boolean finished;
    private boolean cameraRestored;
    private String reportPath = "";
    private String failure = "";
    private Button finishButton;

    private ModernOptBenchmarkScreen(Screen parent) {
        super(Component.translatable("modernopt.benchmark.title"));
        this.parent = parent;
        Player player = minecraft.player;
        this.startX = player.getX();
        this.startY = player.getY();
        this.startZ = player.getZ();
        this.startYaw = player.getYRot();
        this.startPitch = player.getXRot();
        this.csvRows.add("elapsed_ms,segment,frame_time_ms");
    }

    public static void tryStart(Screen parent) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null
                || !minecraft.hasSingleplayerServer() || !minecraft.player.isSpectator()) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                    Component.translatable("modernopt.benchmark.requirements"), false);
            }
            return;
        }
        minecraft.setScreen(new ModernOptBenchmarkScreen(parent));
    }

    @Override
    protected void init() {
        this.finishButton = Button.builder(
                Component.translatable(this.finished ? "gui.done" : "modernopt.benchmark.cancel"),
                button -> {
                    if (!this.finished) finishRun(true);
                    this.minecraft.setScreen(this.parent);
                })
            .bounds(this.width / 2 - 70, this.height - 34, 140, 20)
            .build();
        addRenderableWidget(this.finishButton);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.finished) return;
        if (this.minecraft.level == null || this.minecraft.player == null) {
            finishRun(true);
            return;
        }

        this.elapsedTicks++;
        if (this.elapsedTicks == 1) {
            this.startNanos = System.nanoTime();
        }

        Player player = this.minecraft.player;
        if (this.elapsedTicks <= WARMUP_TICKS) {
            int sweep = (this.elapsedTicks / 20) % 3;
            player.setYRot(this.startYaw + sweep * 45F);
            player.setXRot(new float[]{-8F, -24F, 10F}[sweep]);
            return;
        }

        int routeTick = this.elapsedTicks - WARMUP_TICKS;
        if (routeTick > SEGMENTS * TICKS_PER_SEGMENT) {
            finishRun(false);
            return;
        }

        int routeIndex = routeTick - 1;
        int segment = Math.min(SEGMENTS - 1, routeIndex / TICKS_PER_SEGMENT);
        float yaw = this.startYaw + segment * 45F;
        player.setYRot(yaw);
        player.setXRot(PITCHES[segment]);

        double x = this.startX;
        double z = this.startZ;
        for (int completed = 0; completed < segment; completed++) {
            double previousRadians = Math.toRadians(this.startYaw + completed * 45F);
            x -= Math.sin(previousRadians) * TICKS_PER_SEGMENT * SPEED_PER_TICK;
            z += Math.cos(previousRadians) * TICKS_PER_SEGMENT * SPEED_PER_TICK;
        }
        double segmentDistance = (routeIndex % TICKS_PER_SEGMENT + 1) * SPEED_PER_TICK;
        double radians = Math.toRadians(yaw);
        x -= Math.sin(radians) * segmentDistance;
        z += Math.cos(radians) * segmentDistance;
        double altitude = this.startY + 16.0 + Math.sin(routeTick / 24.0) * 5.0;
        player.setPos(x, altitude, z);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Keep the live world visible behind the benchmark HUD.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        if (this.lastFrameNanos != 0L && !this.finished) {
            double frameMs = (now - this.lastFrameNanos) / 1_000_000.0;
            if (Double.isFinite(frameMs) && frameMs > 0.0 && frameMs < 10_000.0) {
                if (this.elapsedTicks > WARMUP_TICKS) {
                    this.frameTimesMs.add(frameMs);
                    int routeTick = this.elapsedTicks - WARMUP_TICKS;
                    int segment = Math.min(SEGMENTS - 1, Math.max(0, routeTick / TICKS_PER_SEGMENT));
                    long elapsedMs = this.startNanos == 0L ? 0L : (now - this.startNanos) / 1_000_000L;
                    this.csvRows.add(String.format(Locale.ROOT, "%d,%d,%.4f", elapsedMs, segment + 1, frameMs));
                }
            }
        }
        this.lastFrameNanos = now;

        int left = this.width / 2 - 155;
        int top = 18;
        int right = this.width / 2 + 155;
        graphics.fill(left, top, right, top + 116, 0xD9101520);
        graphics.drawCenteredString(this.font, Component.translatable("modernopt.benchmark.title"),
            this.width / 2, top + 9, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, statusText(), this.width / 2, top + 28, 0xFF69D6C5);
        graphics.drawString(this.font, Component.translatable("modernopt.benchmark.progress",
            Math.min(this.elapsedTicks, WARMUP_TICKS + SEGMENTS * TICKS_PER_SEGMENT),
            WARMUP_TICKS + SEGMENTS * TICKS_PER_SEGMENT), left + 12, top + 47, 0xFFFFFFFF);
        graphics.drawString(this.font, Component.translatable("modernopt.benchmark.samples",
            this.frameTimesMs.size()), left + 12, top + 62, 0xFFADB8C8);
        graphics.drawString(this.font, statsText(), left + 12, top + 77, 0xFFFFFFFF);
        if (!this.reportPath.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("modernopt.benchmark.saved"),
                left + 12, top + 94, 0xFF69D6C5);
        } else if (!this.failure.isEmpty()) {
            graphics.drawString(this.font, Component.literal(this.failure), left + 12, top + 94, 0xFFFF7777);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private Component statusText() {
        if (this.finished) return Component.translatable("modernopt.benchmark.finished");
        if (this.elapsedTicks <= WARMUP_TICKS) return Component.translatable("modernopt.benchmark.warmup");
        int segment = Math.min(SEGMENTS, (this.elapsedTicks - WARMUP_TICKS) / TICKS_PER_SEGMENT + 1);
        return Component.translatable("modernopt.benchmark.segment", segment, SEGMENTS);
    }

    private Component statsText() {
        if (this.frameTimesMs.isEmpty()) return Component.translatable("modernopt.benchmark.waiting");
        double average = this.frameTimesMs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        List<Double> sorted = new ArrayList<>(this.frameTimesMs);
        Collections.sort(sorted);
        double p99 = sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(sorted.size() * 0.99)));
        double worst = sorted.get(sorted.size() - 1);
        return Component.translatable("modernopt.benchmark.stats",
            String.format(Locale.ROOT, "%.1f", 1000.0 / average),
            String.format(Locale.ROOT, "%.1f", 1000.0 / p99),
            String.format(Locale.ROOT, "%.1f", worst));
    }

    private void finishRun(boolean cancelled) {
        if (this.finished) return;
        this.finished = true;
        restoreCamera();
        if (!this.csvRows.isEmpty() && this.frameTimesMs.size() > 5) saveReport();
        if (this.finishButton != null) this.finishButton.setMessage(Component.translatable("gui.done"));
        if (cancelled && this.failure.isEmpty()) {
            this.failure = "";
        }
    }

    private void restoreCamera() {
        if (this.cameraRestored) return;
        Player player = this.minecraft.player;
        if (player != null) {
            player.setPos(this.startX, this.startY, this.startZ);
            player.setYRot(this.startYaw);
            player.setXRot(this.startPitch);
        }
        this.cameraRestored = true;
    }

    private void saveReport() {
        try {
            Path directory = this.minecraft.gameDirectory.toPath().resolve("modernopt-benchmarks");
            Files.createDirectories(directory);
            Path report = directory.resolve("modernopt-" + Instant.now().toEpochMilli() + ".csv");
            Files.write(report, this.csvRows, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            this.reportPath = this.minecraft.gameDirectory.toPath().relativize(report).toString();
        } catch (IOException | SecurityException exception) {
            this.failure = "Could not save CSV report";
        }
    }

    @Override
    public void onClose() {
        if (!this.finished) finishRun(true);
        this.minecraft.setScreen(this.parent);
    }
}
