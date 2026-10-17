package net.cr512.modernopt.client;

import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.client.BenchmarkRecorder;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Standalone configuration screen available from the Mods list without Sodium. */
public final class ModernOptConfigScreen extends Screen {
    private enum Page { RENDER, DYNAMIC, OVERLAY, PARTICLES, BENCHMARK }
    private final Screen parent;
    private Page page = Page.RENDER;
    private int columnX;
    private int columnWidth;

    public ModernOptConfigScreen(Screen parent) {
        super(Component.translatable("modernopt.screen.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        columnWidth = Math.min(360, this.width - 24);
        columnX = (this.width - columnWidth) / 2;
        int tabWidth = (columnWidth - 16) / 5;
        int y = 30;
        clearWidgets();
        addRenderableWidget(Button.builder(Component.translatable("modernopt.page.render"), b -> { page = Page.RENDER; rebuild(); })
            .bounds(columnX, y, tabWidth, 20).tooltip(Tooltip.create(Component.translatable("modernopt.page.render.tip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("modernopt.page.dynamic"), b -> { page = Page.DYNAMIC; rebuild(); })
            .bounds(columnX + (tabWidth + 4), y, tabWidth, 20).tooltip(Tooltip.create(Component.translatable("modernopt.page.dynamic.tip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("modernopt.page.overlay"), b -> { page = Page.OVERLAY; rebuild(); })
            .bounds(columnX + (tabWidth + 4) * 2, y, tabWidth, 20).tooltip(Tooltip.create(Component.translatable("modernopt.page.overlay.tip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("modernopt.page.particles"), b -> { page = Page.PARTICLES; rebuild(); })
            .bounds(columnX + (tabWidth + 4) * 3, y, tabWidth, 20).tooltip(Tooltip.create(Component.translatable("modernopt.page.particles.tip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("modernopt.page.benchmark"), b -> { page = Page.BENCHMARK; rebuild(); })
            .bounds(columnX + (tabWidth + 4) * 4, y, tabWidth, 20).tooltip(Tooltip.create(Component.translatable("modernopt.page.benchmark.tip"))).build());

        int yRow = 51;
        switch (page) {
            case RENDER -> {
                addRow(yRow, "modernopt.screen.master", "modernopt.screen.master.tip", toggle(ModernOptConfig.ENABLED)); yRow += 18;
                addRow(yRow, "modernopt.screen.preset", "modernopt.screen.preset.tip", this::cyclePreset); yRow += 18;
                addRow(yRow, "modernopt.screen.scale", "modernopt.screen.scale.tip", this::cycleScale); yRow += 18;
                addRow(yRow, "modernopt.screen.upscaler", "modernopt.screen.upscaler.tip", this::cycleUpscaler); yRow += 18;
                addRow(yRow, "modernopt.screen.fsr1_fp16", "modernopt.screen.fsr1_fp16.tip", toggle(ModernOptConfig.FSR1_FP16_ENABLED)); yRow += 18;
                addRow(yRow, "modernopt.screen.dynamic", "modernopt.screen.dynamic.tip", toggle(ModernOptConfig.DYNAMIC_RESOLUTION_ENABLED)); yRow += 18;
                addRow(yRow, "modernopt.screen.fxaa", "modernopt.screen.fxaa.tip", toggle(ModernOptConfig.FXAA_ENABLED)); yRow += 18;
                addRow(yRow, "modernopt.screen.ssaa", "modernopt.screen.ssaa.tip", toggle(ModernOptConfig.SSAA_ENABLED)); yRow += 18;
                addRow(yRow, "modernopt.screen.msaa", "modernopt.screen.msaa.tip", this::cycleMsaa);
            }
            case DYNAMIC -> {
                addRow(yRow, "modernopt.screen.dynamic", "modernopt.screen.dynamic.tip", toggle(ModernOptConfig.DYNAMIC_RESOLUTION_ENABLED)); yRow += 18;
                addRow(yRow, "modernopt.screen.target_fps", "modernopt.screen.target_fps.tip", this::cycleTargetFps); yRow += 18;
                addRow(yRow, "modernopt.screen.min_scale", "modernopt.screen.min_scale.tip", this::cycleMinScale); yRow += 18;
                addRow(yRow, "modernopt.screen.max_scale", "modernopt.screen.max_scale.tip", this::cycleMaxScale); yRow += 18;
                addRow(yRow, "modernopt.screen.iris_shadow_scale", "modernopt.screen.iris_shadow_scale.tip", toggle(ModernOptConfig.IRIS_SHADOW_SCALE_ENABLED)); yRow += 18;
                addRow(yRow, "modernopt.screen.iris_shadow_resolution", "modernopt.screen.iris_shadow_resolution.tip", this::cycleIrisShadowResolution); yRow += 18;
                addRow(yRow, "modernopt.screen.shadow_skip", "modernopt.screen.shadow_skip.tip", toggle(ModernOptConfig.SHADOW_FRAME_SKIP_ENABLED)); yRow += 18;
                addRow(yRow, "modernopt.screen.shadow_fps", "modernopt.screen.shadow_fps.tip", this::cycleShadowFps); yRow += 18;
                addRow(yRow, "modernopt.screen.profiling", "modernopt.screen.profiling.tip", toggle(ModernOptConfig.PROFILING_ENABLED));
            }
            case OVERLAY -> {
                addRow(yRow, "modernopt.screen.overlay", "modernopt.screen.overlay.tip", toggle(ModernOptConfig.OVERLAY_ENABLED)); yRow += 18;
                addRow(yRow, "modernopt.screen.logging", "modernopt.screen.logging.tip", toggle(ModernOptConfig.LOGGING_ENABLED)); yRow += 18;
                addRow(yRow, "modernopt.screen.log_interval", "modernopt.screen.log_interval.tip", this::cycleLogInterval); yRow += 18;
                addRow(yRow, "modernopt.screen.show_fps", "modernopt.screen.show_fps.tip", toggle(ModernOptConfig.OVERLAY_SHOW_FPS)); yRow += 18;
                addRow(yRow, "modernopt.screen.show_cpu", "modernopt.screen.show_cpu.tip", toggle(ModernOptConfig.OVERLAY_SHOW_CPU)); yRow += 18;
                addRow(yRow, "modernopt.screen.show_frame", "modernopt.screen.show_frame.tip", toggle(ModernOptConfig.OVERLAY_SHOW_FRAME_TIME)); yRow += 18;
                addRow(yRow, "modernopt.screen.show_memory", "modernopt.screen.show_memory.tip", toggle(ModernOptConfig.OVERLAY_SHOW_MEMORY)); yRow += 18;
                addRow(yRow, "modernopt.screen.show_render", "modernopt.screen.show_render.tip", toggle(ModernOptConfig.OVERLAY_SHOW_RENDER_INFO)); yRow += 18;
                addRow(yRow, "modernopt.screen.show_one_percent_low", "modernopt.screen.show_one_percent_low.tip", toggle(ModernOptConfig.OVERLAY_SHOW_ONE_PERCENT_LOW)); yRow += 18;
                addRow(yRow, "modernopt.screen.show_p95", "modernopt.screen.show_p95.tip", toggle(ModernOptConfig.OVERLAY_SHOW_P95_FRAME_TIME)); yRow += 18;
                addRow(yRow, "modernopt.screen.opacity", "modernopt.screen.opacity.tip", this::cycleOpacity); yRow += 18;
                addRow(yRow, "modernopt.screen.position", "modernopt.screen.position.tip", this::cyclePosition);
            }
            case PARTICLES -> {
                addRow(yRow, "modernopt.screen.particle_culling", "modernopt.screen.particle_culling.tip", toggle(ModernOptConfig.PARTICLE_CULLING_ENABLED)); yRow += 18;
                addRow(yRow, "modernopt.screen.particle_distance", "modernopt.screen.particle_distance.tip", this::cycleParticleDistance);
            }
            case BENCHMARK -> {
                addRow(yRow, "modernopt.screen.benchmark_recording", "modernopt.screen.benchmark_recording.tip", this::toggleBenchmarkRecording); yRow += 18;
                addRow(yRow, "modernopt.screen.benchmark_status", "modernopt.screen.benchmark_status.tip", () -> {}); yRow += 18;
                addRow(yRow, "modernopt.screen.logging", "modernopt.screen.logging.tip", toggle(ModernOptConfig.LOGGING_ENABLED)); yRow += 18;
                addRow(yRow, "modernopt.screen.log_interval", "modernopt.screen.log_interval.tip", this::cycleLogInterval); yRow += 18;
                addRow(yRow, "modernopt.screen.profiling", "modernopt.screen.profiling.tip", toggle(ModernOptConfig.PROFILING_ENABLED)); yRow += 18;
                addRow(yRow, "modernopt.screen.diag_sample_interval", "modernopt.screen.diag_sample_interval.tip", this::cycleDiagnosticSampleInterval); yRow += 18;
                addRow(yRow, "modernopt.screen.benchmark_duration", "modernopt.screen.benchmark_duration.tip", this::cycleBenchmarkDuration); yRow += 18;
                addRow(yRow, "modernopt.screen.benchmark_cpu", "modernopt.screen.benchmark_cpu.tip", toggle(ModernOptConfig.BENCHMARK_CAPTURE_CPU)); yRow += 18;
                addRow(yRow, "modernopt.screen.benchmark_memory", "modernopt.screen.benchmark_memory.tip", toggle(ModernOptConfig.BENCHMARK_CAPTURE_MEMORY));
            }
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(columnX, this.height - 22, columnWidth, 18).build());
    }

    private void addRow(int y, String name, String tip, Runnable action) {
        String current = currentValue(name);
        addRenderableWidget(Button.builder(Component.literal(Component.translatable(name).getString() + ": " + current), b -> {
            action.run();
            save();
            rebuild();
        }).bounds(columnX, y, columnWidth, 17).tooltip(Tooltip.create(Component.translatable(tip))).build());
    }

    private Runnable toggle(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue value) {
        return () -> value.set(!value.get());
    }

    private String currentValue(String key) {
        return switch (key) {
            case "modernopt.screen.master" -> onOff(ModernOptConfig.ENABLED.get());
            case "modernopt.screen.dynamic" -> onOff(ModernOptConfig.DYNAMIC_RESOLUTION_ENABLED.get());
            case "modernopt.screen.target_fps" -> ModernOptConfig.DYNAMIC_TARGET_FPS.get() + " FPS";
            case "modernopt.screen.min_scale" -> ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get() + "%";
            case "modernopt.screen.max_scale" -> ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get() + "%";
            case "modernopt.screen.iris_shadow_scale" -> onOff(ModernOptConfig.IRIS_SHADOW_SCALE_ENABLED.get());
            case "modernopt.screen.iris_shadow_resolution" -> ModernOptConfig.IRIS_SHADOW_RESOLUTION.get() + " px";
            case "modernopt.screen.shadow_skip" -> onOff(ModernOptConfig.SHADOW_FRAME_SKIP_ENABLED.get());
            case "modernopt.screen.shadow_fps" -> ModernOptConfig.SHADOW_UPDATE_FPS.get() + " FPS";
            case "modernopt.screen.profiling" -> onOff(ModernOptConfig.PROFILING_ENABLED.get());
            case "modernopt.screen.show_one_percent_low" -> onOff(ModernOptConfig.OVERLAY_SHOW_ONE_PERCENT_LOW.get());
            case "modernopt.screen.show_p95" -> onOff(ModernOptConfig.OVERLAY_SHOW_P95_FRAME_TIME.get());
            case "modernopt.screen.opacity" -> ModernOptConfig.OVERLAY_BACKGROUND_OPACITY.get() + "%";
            case "modernopt.screen.benchmark_recording" -> onOff(BenchmarkRecorder.isRecording());
            case "modernopt.screen.benchmark_status" -> BenchmarkRecorder.status();
            case "modernopt.screen.diag_sample_interval" -> "1 / " + ModernOptConfig.DIAGNOSTICS_SAMPLE_INTERVAL_FRAMES.get() + " frames";
            case "modernopt.screen.benchmark_duration" -> ModernOptConfig.BENCHMARK_DURATION_SECONDS.get() == 0 ? "Manual" : ModernOptConfig.BENCHMARK_DURATION_SECONDS.get() + " s";
            case "modernopt.screen.benchmark_cpu" -> onOff(ModernOptConfig.BENCHMARK_CAPTURE_CPU.get());
            case "modernopt.screen.benchmark_memory" -> onOff(ModernOptConfig.BENCHMARK_CAPTURE_MEMORY.get());
            case "modernopt.screen.fxaa" -> onOff(ModernOptConfig.FXAA_ENABLED.get());
            case "modernopt.screen.ssaa" -> onOff(ModernOptConfig.SSAA_ENABLED.get());
            case "modernopt.screen.overlay" -> onOff(ModernOptConfig.OVERLAY_ENABLED.get());
            case "modernopt.screen.logging" -> onOff(ModernOptConfig.LOGGING_ENABLED.get());
            case "modernopt.screen.show_fps" -> onOff(ModernOptConfig.OVERLAY_SHOW_FPS.get());
            case "modernopt.screen.show_cpu" -> onOff(ModernOptConfig.OVERLAY_SHOW_CPU.get());
            case "modernopt.screen.show_frame" -> onOff(ModernOptConfig.OVERLAY_SHOW_FRAME_TIME.get());
            case "modernopt.screen.show_memory" -> onOff(ModernOptConfig.OVERLAY_SHOW_MEMORY.get());
            case "modernopt.screen.show_render" -> onOff(ModernOptConfig.OVERLAY_SHOW_RENDER_INFO.get());
            case "modernopt.screen.particle_culling" -> onOff(ModernOptConfig.PARTICLE_CULLING_ENABLED.get());
            case "modernopt.screen.scale" -> ModernOptConfig.RENDER_SCALE_PERCENT.get() + "%";
            case "modernopt.screen.upscaler" -> upscalerName(ModernOptConfig.UPSCALER_MODE.get());
            case "modernopt.screen.fsr1_fp16" -> onOff(ModernOptConfig.FSR1_FP16_ENABLED.get());
            case "modernopt.screen.preset" -> presetName();
            case "modernopt.screen.msaa" -> ModernOptConfig.MSAA_SAMPLES.get() == 0 ? "Off" : ModernOptConfig.MSAA_SAMPLES.get() + "x";
            case "modernopt.screen.log_interval" -> ModernOptConfig.LOG_INTERVAL_SECONDS.get() + " s";
            case "modernopt.screen.position" -> Component.translatable(switch (ModernOptConfig.OVERLAY_POSITION.get()) {
                case 1 -> "modernopt.position.top_right";
                case 2 -> "modernopt.position.bottom_left";
                case 3 -> "modernopt.position.bottom_right";
                default -> "modernopt.position.top_left";
            }).getString();
            case "modernopt.screen.particle_distance" -> ModernOptConfig.PARTICLE_RENDER_DISTANCE.get() + " blocks";
            default -> "";
        };
    }

    private void cyclePreset() {
        int scale = ModernOptConfig.RENDER_SCALE_PERCENT.get();
        int mode = ModernOptConfig.UPSCALER_MODE.get();
        if (scale == 100 && mode == 2) { setPreset(75, 1); }
        else if (scale == 75 && mode == 1) { setPreset(60, 1); }
        else if (scale == 60 && mode == 1) { setPreset(50, 2); }
        else if (scale == 50 && mode == 2) { setPreset(35, 0); }
        else setPreset(100, 2);
    }

    private void setPreset(int scale, int upscaler) {
        ModernOptConfig.RENDER_SCALE_PERCENT.set(scale);
        ModernOptConfig.UPSCALER_MODE.set(upscaler);
    }

    private String presetName() {
        int scale = ModernOptConfig.RENDER_SCALE_PERCENT.get();
        int mode = ModernOptConfig.UPSCALER_MODE.get();
        if (scale == 100 && mode == 2) return Component.translatable("modernopt.profile.native").getString();
        if (scale == 75 && mode == 1) return Component.translatable("modernopt.profile.quality").getString();
        if (scale == 60 && mode == 1) return Component.translatable("modernopt.profile.balanced").getString();
        if (scale == 50 && mode == 2) return Component.translatable("modernopt.profile.performance").getString();
        if (scale == 35 && mode == 0) return Component.translatable("modernopt.profile.ultra_performance").getString();
        return Component.translatable("modernopt.profile.custom").getString();
    }

    private void cycleScale() {
        int[] values = {100, 90, 85, 80, 75, 70, 60, 50, 40, 35, 25};
        int current = ModernOptConfig.RENDER_SCALE_PERCENT.get();
        int next = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == current) next = (i + 1) % values.length;
        ModernOptConfig.RENDER_SCALE_PERCENT.set(values[next]);
    }

    private void cycleUpscaler() {
        ModernOptConfig.UPSCALER_MODE.set((ModernOptConfig.UPSCALER_MODE.get() + 1) % 5);
    }

    private void cycleMsaa() {
        int[] values = {0, 2, 4, 8};
        int next = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == ModernOptConfig.MSAA_SAMPLES.get()) next = (i + 1) % values.length;
        ModernOptConfig.MSAA_SAMPLES.set(values[next]);
    }

    private void cycleLogInterval() {
        int[] values = {1, 2, 3, 5, 10, 15, 30, 60};
        ModernOptConfig.LOG_INTERVAL_SECONDS.set(nextValue(values, ModernOptConfig.LOG_INTERVAL_SECONDS.get()));
    }

    private int nextValue(int[] values, int current) {
        for (int i = 0; i < values.length; i++) if (values[i] == current) return values[(i + 1) % values.length];
        return values[0];
    }

    private void cycleTargetFps() {
        ModernOptConfig.DYNAMIC_TARGET_FPS.set(nextValue(new int[]{30, 45, 60, 90, 120, 144, 165, 240, 360, 540}, ModernOptConfig.DYNAMIC_TARGET_FPS.get()));
    }

    private void cycleMinScale() {
        int next = nextValue(new int[]{25, 35, 50, 60, 75, 100}, ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get());
        ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.set(next);
        if (ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get() < next) ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.set(next);
    }

    private void cycleMaxScale() {
        int next = nextValue(new int[]{25, 35, 50, 60, 75, 100}, ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get());
        ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.set(next);
        if (ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get() > next) ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.set(next);
    }

    private void cycleIrisShadowResolution() {
        ModernOptConfig.IRIS_SHADOW_RESOLUTION.set(nextValue(new int[]{512, 768, 1024, 1536, 2048, 3072, 4096, 8192}, ModernOptConfig.IRIS_SHADOW_RESOLUTION.get()));
    }

    private void cycleShadowFps() {
        ModernOptConfig.SHADOW_UPDATE_FPS.set(nextValue(new int[]{40, 60, 90, 120, 144, 180, 240}, ModernOptConfig.SHADOW_UPDATE_FPS.get()));
    }

    private void cycleOpacity() {
        ModernOptConfig.OVERLAY_BACKGROUND_OPACITY.set(nextValue(new int[]{0, 20, 40, 56, 70, 85, 100}, ModernOptConfig.OVERLAY_BACKGROUND_OPACITY.get()));
    }

    private void cycleDiagnosticSampleInterval() {
        ModernOptConfig.DIAGNOSTICS_SAMPLE_INTERVAL_FRAMES.set(nextValue(new int[]{1, 2, 3, 5, 10}, ModernOptConfig.DIAGNOSTICS_SAMPLE_INTERVAL_FRAMES.get()));
    }

    private void cycleBenchmarkDuration() {
        ModernOptConfig.BENCHMARK_DURATION_SECONDS.set(nextValue(new int[]{0, 15, 30, 60, 120, 300}, ModernOptConfig.BENCHMARK_DURATION_SECONDS.get()));
    }

    private void toggleBenchmarkRecording() {
        if (BenchmarkRecorder.isRecording()) {
            BenchmarkRecorder.stop();
        } else if (ModernOptConfig.ENABLED.get()) {
            BenchmarkRecorder.start();
        } else {
            // The master switch is a true kill switch for all ModernOPT features.
            ModernOptConfig.ENABLED.set(true);
            BenchmarkRecorder.start();
        }
    }

    private void cyclePosition() {
        ModernOptConfig.OVERLAY_POSITION.set((ModernOptConfig.OVERLAY_POSITION.get() + 1) % 4);
    }

    private void cycleParticleDistance() {
        int[] values = {32, 48, 64, 96, 128, 192, 256};
        int next = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == ModernOptConfig.PARTICLE_RENDER_DISTANCE.get()) next = (i + 1) % values.length;
        ModernOptConfig.PARTICLE_RENDER_DISTANCE.set(values[next]);
    }

    private String upscalerName(int mode) {
        return switch (mode) {
            case 0 -> Component.translatable("modernopt.upscaler.none").getString();
            case 1 -> Component.translatable("modernopt.upscaler.fsr1").getString();
            case 2 -> Component.translatable("modernopt.upscaler.bilinear").getString();
            case 3 -> Component.translatable("modernopt.upscaler.nearest").getString();
            case 4 -> Component.translatable("modernopt.upscaler.softscale").getString();
            default -> "Unknown";
        };
    }

    private String onOff(boolean value) { return Component.translatable(value ? "modernopt.value.on" : "modernopt.value.off").getString(); }

    private void save() {
        ModernOptConfig.SPEC.save();
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        save();
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
