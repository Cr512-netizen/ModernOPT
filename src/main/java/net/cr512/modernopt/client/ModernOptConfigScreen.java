// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.client;

import java.util.ArrayList;
import java.util.List;

import net.cr512.modernopt.compat.ShadowMapResolution;
import net.cr512.modernopt.config.MsaaLevel;
import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.config.ModernOptSettings;
import net.cr512.modernopt.config.UpscalerType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * ModernOPT options screen, reachable from the keybind and from the Sodium options page.
 *
 * <p>Options are laid out in two columns and the content scrolls when it does not fit, so every
 * setting stays reachable on small GUI scales. Values are read from the live config after a
 * refresh, so changes made from the Sodium page are reflected here and vice versa.</p>
 */
public class ModernOptConfigScreen extends Screen {

    private static final int COLUMN_WIDTH = 172;
    private static final int COLUMN_GAP = 8;
    private static final int ROW_HEIGHT = 24;

    private final Screen parent;
    private final List<AbstractWidget> leftColumn = new ArrayList<>();
    private final List<AbstractWidget> rightColumn = new ArrayList<>();
    private int scroll;
    private int contentHeight;
    private int viewportTop;
    private int viewportBottom;
    private Button disableButton;

    public ModernOptConfigScreen(Screen parent) {
        super(Component.translatable("modernopt.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        this.leftColumn.clear();
        this.rightColumn.clear();
        ModernOptSettings.refresh();

        this.viewportTop = 58;
        this.viewportBottom = this.height - 34;

        // Master switch, always visible: one click fully disables the mod.
        this.disableButton = this.addRenderableWidget(Button.builder(disableLabel(),
                        button -> {
                            boolean enabled = !ModernOptSettings.enabled();
                            ModernOptConfig.ENABLED.set(enabled);
                            ModernOptSettings.refresh();
                            ShadowMapResolution.requestReload();
                            // Rebuild so every control reflects the new state immediately.
                            this.rebuildWidgets();
                        })
                .bounds(this.width / 2 - COLUMN_WIDTH - COLUMN_GAP / 2, 30, COLUMN_WIDTH * 2 + COLUMN_GAP, 20)
                .build());

        // --- left column: resolution and reconstruction ---
        addLeft(CycleButton.onOffBuilder(ModernOptSettings.enabled())
                .create(0, 0, COLUMN_WIDTH, 20, Component.translatable("modernopt.option.enabled"),
                        (button, value) -> {
                            ModernOptConfig.ENABLED.set(value);
                            ModernOptSettings.refresh();
                            ShadowMapResolution.requestReload();
                            disableButton.setMessage(disableLabel());
                        }));

        addLeft(new IntSlider(0, 0, COLUMN_WIDTH, 20,
                Component.translatable("modernopt.option.renderScale"), 25, 100,
                ModernOptSettings.renderScalePercent(),
                value -> {
                    ModernOptConfig.RENDER_SCALE_PERCENT.set(value);
                    ModernOptSettings.refresh();
                },
                value -> value + "%"));

        addLeft(CycleButton.<UpscalerType>builder(type -> Component.literal(type.label()))
                .withValues(UpscalerType.values())
                .withInitialValue(ModernOptSettings.upscaler())
                .create(0, 0, COLUMN_WIDTH, 20, Component.translatable("modernopt.option.upscaler"),
                        (button, value) -> {
                            ModernOptConfig.UPSCALER.set(value);
                            ModernOptSettings.refresh();
                        }));

        addLeft(new IntSlider(0, 0, COLUMN_WIDTH, 20,
                Component.translatable("modernopt.option.hudScale"), 50, 100,
                ModernOptSettings.hudScalePercent(),
                value -> {
                    ModernOptConfig.HUD_SCALE_PERCENT.set(value);
                    ModernOptSettings.refresh();
                },
                value -> value + "%"));

        addLeft(CycleButton.onOffBuilder(ModernOptSettings.scaleWholeWindow())
                .create(0, 0, COLUMN_WIDTH, 20, Component.translatable("modernopt.option.scaleWholeWindow"),
                        (button, value) -> {
                            ModernOptConfig.SCALE_WHOLE_WINDOW.set(value);
                            ModernOptSettings.refresh();
                        }));

        addLeft(new IntSlider(0, 0, COLUMN_WIDTH, 20,
                Component.translatable("modernopt.option.softness"), 0, 100,
                ModernOptSettings.softness(),
                value -> {
                    ModernOptConfig.SOFTNESS.set(value);
                    ModernOptSettings.refresh();
                },
                value -> value + "%"));

        addLeft(new IntSlider(0, 0, COLUMN_WIDTH, 20,
                Component.translatable("modernopt.option.fsrEasuSharpness"), 0, 100,
                ModernOptSettings.fsrEasuSharpness(),
                value -> {
                    ModernOptConfig.FSR_EASU_SHARPNESS.set(value);
                    ModernOptSettings.refresh();
                },
                value -> value + "%"));

        addLeft(new IntSlider(0, 0, COLUMN_WIDTH, 20,
                Component.translatable("modernopt.option.fsrRcasSharpness"), 0, 100,
                ModernOptSettings.fsrRcasSharpness(),
                value -> {
                    ModernOptConfig.FSR_RCAS_SHARPNESS.set(value);
                    ModernOptSettings.refresh();
                },
                value -> value + "%"));

        addLeft(CycleButton.<MsaaLevel>builder(level -> Component.literal(level.label()))
                .withValues(MsaaLevel.values())
                .withInitialValue(ModernOptSettings.msaa())
                .create(0, 0, COLUMN_WIDTH, 20, Component.translatable("modernopt.option.msaa"),
                        (button, value) -> {
                            ModernOptConfig.MSAA.set(value);
                            ModernOptSettings.refresh();
                        }));

        addLeft(CycleButton.onOffBuilder(ModernOptSettings.ssaa())
                .create(0, 0, COLUMN_WIDTH, 20, Component.translatable("modernopt.option.ssaa"),
                        (button, value) -> {
                            ModernOptConfig.SSAA.set(value);
                            ModernOptSettings.refresh();
                        }));

        addLeft(CycleButton.onOffBuilder(ModernOptSettings.fxaa())
                .create(0, 0, COLUMN_WIDTH, 20, Component.translatable("modernopt.option.fxaa"),
                        (button, value) -> {
                            ModernOptConfig.FXAA.set(value);
                            ModernOptSettings.refresh();
                        }));

        addLeft(CycleButton.onOffBuilder(ModernOptSettings.overlay())
                .create(0, 0, COLUMN_WIDTH, 20, Component.translatable("modernopt.option.overlay"),
                        (button, value) -> {
                            ModernOptConfig.SHOW_OVERLAY.set(value);
                            ModernOptSettings.refresh();
                        }));

        // --- right column: shadows and MSR2 ---
        int[] shadowSizes = ShadowMapResolution.allowedValues();
        addRight(new IntSlider(0, 0, COLUMN_WIDTH, 20,
                Component.translatable("modernopt.option.shadowMapResolution"), 0, shadowSizes.length - 1,
                shadowResolutionIndex(shadowSizes, ShadowMapResolution.desired()),
                value -> {
                    ModernOptConfig.SHADOW_MAP_RESOLUTION.set(shadowSizes[value]);
                    ModernOptSettings.refresh();
                },
                value -> shadowSizes[value] + " x " + shadowSizes[value]));

        addRight(new IntSlider(0, 0, COLUMN_WIDTH, 20,
                Component.translatable("modernopt.option.shadowUpdateInterval"), 1, 4,
                ModernOptSettings.shadowUpdateInterval(),
                value -> {
                    ModernOptConfig.SHADOW_UPDATE_INTERVAL.set(value);
                    ModernOptSettings.refresh();
                },
                value -> value <= 1 ? "every frame" : "every " + value + " frames"));

        addRight(CycleButton.onOffBuilder(ModernOptSettings.msr2Enabled())
                .create(0, 0, COLUMN_WIDTH, 20, Component.translatable("modernopt.option.msrEnabled"),
                        (button, value) -> {
                            ModernOptConfig.MSR2_ENABLED.set(value);
                            ModernOptSettings.refresh();
                        }));

        addRight(new IntSlider(0, 0, COLUMN_WIDTH, 20,
                Component.translatable("modernopt.option.msrVarianceScale"), 5, 40,
                (int) (ModernOptSettings.msr2VarianceScale() * 10),
                value -> {
                    ModernOptConfig.MSR2_VARIANCE_SCALE.set(value / 10.0);
                    ModernOptSettings.refresh();
                },
                value -> (value / 10.0) + "x"));

        addRight(CycleButton.onOffBuilder(ModernOptSettings.msr2EdgeAware())
                .create(0, 0, COLUMN_WIDTH, 20, Component.translatable("modernopt.option.msrEdgeAware"),
                        (button, value) -> {
                            ModernOptConfig.MSR2_EDGE_AWARE.set(value);
                            ModernOptSettings.refresh();
                        }));

        addRight(new IntSlider(0, 0, COLUMN_WIDTH, 20,
                Component.translatable("modernopt.option.msrSharpness"), 0, 100,
                ModernOptSettings.msr2Sharpness(),
                value -> {
                    ModernOptConfig.MSR2_SHARPNESS.set(value);
                    ModernOptSettings.refresh();
                },
                value -> value + "%"));

        addRight(new IntSlider(0, 0, COLUMN_WIDTH, 20,
                Component.translatable("modernopt.option.msrDepthThreshold"), 5, 100,
                (int) (ModernOptSettings.msr2DepthThreshold() * 1000),
                value -> {
                    ModernOptConfig.MSR2_DEPTH_THRESHOLD.set(value / 1000.0);
                    ModernOptSettings.refresh();
                },
                value -> (value / 1000.0) + ""));

        addRight(new IntSlider(0, 0, COLUMN_WIDTH, 20,
                Component.translatable("modernopt.option.msrHistoryWeight"), 50, 95,
                (int) (ModernOptSettings.msr2HistoryWeight() * 100),
                value -> {
                    ModernOptConfig.MSR2_HISTORY_WEIGHT.set(value / 100.0);
                    ModernOptSettings.refresh();
                },
                value -> (value / 100.0) + ""));

        addRight(new IntSlider(0, 0, COLUMN_WIDTH, 20,
                Component.translatable("modernopt.option.fp16"), 0, 1,
                ModernOptSettings.fp16World() ? 1 : 0,
                value -> {
                    ModernOptConfig.FP16_WORLD.set(value == 1);
                    ModernOptSettings.refresh();
                },
                value -> value == 1 ? "on" : "off"));

        addRight(CycleButton.onOffBuilder(ModernOptConfig.LOG_FPS.get())
                .create(0, 0, COLUMN_WIDTH, 20, Component.translatable("modernopt.option.logFps"),
                        (button, value) -> {
                            ModernOptConfig.LOG_FPS.set(value);
                            ModernOptSettings.refresh();
                        }));

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(this.width / 2 - COLUMN_WIDTH - COLUMN_GAP / 2, this.height - 28,
                        COLUMN_WIDTH * 2 + COLUMN_GAP, 20)
                .build());

        this.scroll = 0;
        layoutColumns();
    }

    private static int shadowResolutionIndex(int[] sizes, int value) {
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i] == value) {
                return i;
            }
        }
        return 0;
    }

    private Component disableLabel() {
        return Component.translatable(ModernOptSettings.enabled()
                ? "modernopt.option.disable" : "modernopt.option.enable");
    }

    private void addLeft(AbstractWidget widget) {
        this.leftColumn.add(this.addRenderableWidget(widget));
    }

    private void addRight(AbstractWidget widget) {
        this.rightColumn.add(this.addRenderableWidget(widget));
    }

    private void layoutColumns() {
        int rows = Math.max(this.leftColumn.size(), this.rightColumn.size());
        this.contentHeight = rows * ROW_HEIGHT;
        int visible = Math.max(0, this.viewportBottom - this.viewportTop);
        int maxScroll = Math.max(0, this.contentHeight - visible);
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll));

        int totalWidth = COLUMN_WIDTH * 2 + COLUMN_GAP;
        int leftX = this.width / 2 - totalWidth / 2;
        int rightX = leftX + COLUMN_WIDTH + COLUMN_GAP;

        for (int i = 0; i < this.leftColumn.size(); i++) {
            position(this.leftColumn.get(i), leftX, this.viewportTop + i * ROW_HEIGHT - this.scroll);
        }
        for (int i = 0; i < this.rightColumn.size(); i++) {
            position(this.rightColumn.get(i), rightX, this.viewportTop + i * ROW_HEIGHT - this.scroll);
        }
    }

    private void position(AbstractWidget widget, int x, int y) {
        widget.setX(x);
        widget.setY(y);
        widget.visible = y >= this.viewportTop - 1 && y + widget.getHeight() <= this.viewportBottom + 1;
        widget.active = widget.visible;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.contentHeight > this.viewportBottom - this.viewportTop) {
            this.scroll -= (int) Math.round(scrollY * ROW_HEIGHT);
            layoutColumns();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);

        int visible = this.viewportBottom - this.viewportTop;
        if (this.contentHeight > visible) {
            int barX = this.width / 2 + COLUMN_WIDTH + COLUMN_GAP / 2 + 3;
            int barHeight = Math.max(16, visible * visible / this.contentHeight);
            int barY = this.viewportTop + (visible - barHeight) * this.scroll
                    / Math.max(1, this.contentHeight - visible);
            graphics.fill(barX, this.viewportTop, barX + 3, this.viewportBottom, 0x40000000);
            graphics.fill(barX, barY, barX + 3, barY + barHeight, 0xFF9E9E9E);
        }
    }

    @Override
    public void onClose() {
        ModernOptConfig.SPEC.save();
        ModernOptSettings.refresh();
        // Defer the pack reload until the screen closes: a slider drag changes the value many
        // times and must not recompile the pack on every step.
        if (ShadowMapResolution.needsReload()) {
            ShadowMapResolution.requestReload();
        }
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}
