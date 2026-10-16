package net.cr512.modernopt.client;

import net.cr512.modernopt.config.ModernOptConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Standalone configuration screen available from the Mods list without Sodium. */
public final class ModernOptConfigScreen extends Screen {
    private enum Page { RENDER, OVERLAY, PARTICLES }
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
        int tabWidth = (columnWidth - 8) / 3;
        int y = 30;
        // Replace the three temporary full-width tab buttons with a compact tab row.
        clearWidgets();
        addRenderableWidget(Button.builder(Component.translatable("modernopt.page.render"), b -> { page = Page.RENDER; rebuild(); })
            .bounds(columnX, y, tabWidth, 20).tooltip(Tooltip.create(Component.translatable("modernopt.page.render.tip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("modernopt.page.overlay"), b -> { page = Page.OVERLAY; rebuild(); })
            .bounds(columnX + tabWidth + 4, y, tabWidth, 20).tooltip(Tooltip.create(Component.translatable("modernopt.page.overlay.tip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("modernopt.page.particles"), b -> { page = Page.PARTICLES; rebuild(); })
            .bounds(columnX + (tabWidth + 4) * 2, y, tabWidth, 20).tooltip(Tooltip.create(Component.translatable("modernopt.page.particles.tip"))).build());

        int yRow = 53;
        switch (page) {
            case RENDER -> {
                addRow(yRow, "modernopt.screen.master", "modernopt.screen.master.tip", toggle(ModernOptConfig.ENABLED)); yRow += 19;
                addRow(yRow, "modernopt.screen.preset", "modernopt.screen.preset.tip", this::cyclePreset); yRow += 24;
                addRow(yRow, "modernopt.screen.scale", "modernopt.screen.scale.tip", this::cycleScale); yRow += 24;
                addRow(yRow, "modernopt.screen.upscaler", "modernopt.screen.upscaler.tip", this::cycleUpscaler); yRow += 24;
                addRow(yRow, "modernopt.screen.dynamic", "modernopt.screen.dynamic.tip", toggle(ModernOptConfig.DYNAMIC_RESOLUTION_ENABLED)); yRow += 24;
                addRow(yRow, "modernopt.screen.fxaa", "modernopt.screen.fxaa.tip", toggle(ModernOptConfig.FXAA_ENABLED)); yRow += 24;
                addRow(yRow, "modernopt.screen.ssaa", "modernopt.screen.ssaa.tip", toggle(ModernOptConfig.SSAA_ENABLED)); yRow += 24;
                addRow(yRow, "modernopt.screen.msaa", "modernopt.screen.msaa.tip", this::cycleMsaa);
            }
            case OVERLAY -> {
                addRow(yRow, "modernopt.screen.overlay", "modernopt.screen.overlay.tip", toggle(ModernOptConfig.OVERLAY_ENABLED)); yRow += 24;
                addRow(yRow, "modernopt.screen.logging", "modernopt.screen.logging.tip", toggle(ModernOptConfig.LOGGING_ENABLED)); yRow += 24;
                addRow(yRow, "modernopt.screen.log_interval", "modernopt.screen.log_interval.tip", this::cycleLogInterval); yRow += 24;
                addRow(yRow, "modernopt.screen.show_fps", "modernopt.screen.show_fps.tip", toggle(ModernOptConfig.OVERLAY_SHOW_FPS)); yRow += 24;
                addRow(yRow, "modernopt.screen.show_frame", "modernopt.screen.show_frame.tip", toggle(ModernOptConfig.OVERLAY_SHOW_FRAME_TIME)); yRow += 24;
                addRow(yRow, "modernopt.screen.show_memory", "modernopt.screen.show_memory.tip", toggle(ModernOptConfig.OVERLAY_SHOW_MEMORY)); yRow += 24;
                addRow(yRow, "modernopt.screen.show_render", "modernopt.screen.show_render.tip", toggle(ModernOptConfig.OVERLAY_SHOW_RENDER_INFO)); yRow += 24;
                addRow(yRow, "modernopt.screen.position", "modernopt.screen.position.tip", this::cyclePosition);
            }
            case PARTICLES -> {
                addRow(yRow, "modernopt.screen.particle_culling", "modernopt.screen.particle_culling.tip", toggle(ModernOptConfig.PARTICLE_CULLING_ENABLED)); yRow += 24;
                addRow(yRow, "modernopt.screen.particle_distance", "modernopt.screen.particle_distance.tip", this::cycleParticleDistance);
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
        }).bounds(columnX, y, columnWidth, 18).tooltip(Tooltip.create(Component.translatable(tip))).build());
    }

    private Runnable toggle(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue value) {
        return () -> value.set(!value.get());
    }

    private String currentValue(String key) {
        return switch (key) {
            case "modernopt.screen.master" -> onOff(ModernOptConfig.ENABLED.get());
            case "modernopt.screen.dynamic" -> onOff(ModernOptConfig.DYNAMIC_RESOLUTION_ENABLED.get());
            case "modernopt.screen.fxaa" -> onOff(ModernOptConfig.FXAA_ENABLED.get());
            case "modernopt.screen.ssaa" -> onOff(ModernOptConfig.SSAA_ENABLED.get());
            case "modernopt.screen.overlay" -> onOff(ModernOptConfig.OVERLAY_ENABLED.get());
            case "modernopt.screen.logging" -> onOff(ModernOptConfig.LOGGING_ENABLED.get());
            case "modernopt.screen.show_fps" -> onOff(ModernOptConfig.OVERLAY_SHOW_FPS.get());
            case "modernopt.screen.show_frame" -> onOff(ModernOptConfig.OVERLAY_SHOW_FRAME_TIME.get());
            case "modernopt.screen.show_memory" -> onOff(ModernOptConfig.OVERLAY_SHOW_MEMORY.get());
            case "modernopt.screen.show_render" -> onOff(ModernOptConfig.OVERLAY_SHOW_RENDER_INFO.get());
            case "modernopt.screen.particle_culling" -> onOff(ModernOptConfig.PARTICLE_CULLING_ENABLED.get());
            case "modernopt.screen.scale" -> ModernOptConfig.RENDER_SCALE_PERCENT.get() + "%";
            case "modernopt.screen.upscaler" -> upscalerName(ModernOptConfig.UPSCALER_MODE.get());
            case "modernopt.screen.preset" -> presetName();
            case "modernopt.screen.msaa" -> ModernOptConfig.MSAA_SAMPLES.get() == 0 ? "Off" : ModernOptConfig.MSAA_SAMPLES.get() + "x";
            case "modernopt.screen.log_interval" -> ModernOptConfig.LOG_INTERVAL_SECONDS.get() + " s";
            case "modernopt.screen.position" -> Integer.toString(ModernOptConfig.OVERLAY_POSITION.get() + 1);
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
        if (scale == 100 && mode == 2) return "Native";
        if (scale == 75 && mode == 1) return "Quality";
        if (scale == 60 && mode == 1) return "Balanced";
        if (scale == 50 && mode == 2) return "Performance";
        if (scale == 35 && mode == 0) return "Ultra performance";
        return "Custom";
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
        int[] values = {0, 2, 4, 8, 16};
        int next = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == ModernOptConfig.MSAA_SAMPLES.get()) next = (i + 1) % values.length;
        ModernOptConfig.MSAA_SAMPLES.set(values[next]);
    }

    private void cycleLogInterval() {
        int value = ModernOptConfig.LOG_INTERVAL_SECONDS.get();
        ModernOptConfig.LOG_INTERVAL_SECONDS.set(value >= 10 ? 1 : value + 1);
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
            case 0 -> "None";
            case 1 -> "FSR 1";
            case 2 -> "Bilinear";
            case 3 -> "Nearest";
            case 4 -> "SoftScale";
            default -> "Unknown";
        };
    }

    private String onOff(boolean value) { return value ? "On" : "Off"; }

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
        graphics.drawCenteredString(this.font, Component.translatable("modernopt.screen.subtitle"), this.width / 2, this.height - 43, 0xAABBD0);
    }

    @Override
    public void onClose() {
        save();
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
