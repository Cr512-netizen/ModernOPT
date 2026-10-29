package net.cr512.modernopt.client;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

import net.cr512.modernopt.config.MsaaLevel;
import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.config.ModernOptSettings;
import net.cr512.modernopt.config.UpscalerType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** ModernOPT options screen, reachable from the keybind and from the Sodium options page. */
public class ModernOptConfigScreen extends Screen {

    private final Screen parent;

    public ModernOptConfigScreen(Screen parent) {
        super(Component.translatable("modernopt.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.clearWidgets();

        int width = 310;
        int left = this.width / 2 - width / 2;
        int y = 34;
        int step = 24;

        this.addRenderableWidget(CycleButton.onOffBuilder(ModernOptSettings.enabled())
                .create(left, y, width, 20, Component.translatable("modernopt.option.enabled"), (button, value) -> {
                    ModernOptConfig.ENABLED.set(value);
                    ModernOptSettings.refresh();
                }));

        y += step;
        this.addRenderableWidget(new IntSlider(left, y, width, 20,
                Component.translatable("modernopt.option.renderScale"), 25, 100,
                ModernOptSettings.renderScalePercent(),
                value -> {
                    ModernOptConfig.RENDER_SCALE_PERCENT.set(value);
                    ModernOptSettings.refresh();
                },
                value -> value + "%"));

        y += step;
        this.addRenderableWidget(CycleButton.<UpscalerType>builder(type -> Component.literal(type.label()))
                .withValues(UpscalerType.values())
                .withInitialValue(ModernOptSettings.upscaler())
                .create(left, y, width, 20, Component.translatable("modernopt.option.upscaler"), (button, value) -> {
                    ModernOptConfig.UPSCALER.set(value);
                    ModernOptSettings.refresh();
                }));

        y += step;
        this.addRenderableWidget(new IntSlider(left, y, width, 20,
                Component.translatable("modernopt.option.softness"), 0, 100,
                ModernOptSettings.softness(),
                value -> {
                    ModernOptConfig.SOFTNESS.set(value);
                    ModernOptSettings.refresh();
                },
                value -> value + "%"));

        y += step;
        this.addRenderableWidget(new IntSlider(left, y, width, 20,
                Component.translatable("modernopt.option.fsrEasuSharpness"), 0, 100,
                ModernOptSettings.fsrEasuSharpness(),
                value -> {
                    ModernOptConfig.FSR_EASU_SHARPNESS.set(value);
                    ModernOptSettings.refresh();
                },
                value -> value + "%"));

        y += step;
        this.addRenderableWidget(new IntSlider(left, y, width, 20,
                Component.translatable("modernopt.option.fsrRcasSharpness"), 0, 100,
                ModernOptSettings.fsrRcasSharpness(),
                value -> {
                    ModernOptConfig.FSR_RCAS_SHARPNESS.set(value);
                    ModernOptSettings.refresh();
                },
                value -> value + "%"));

        y += step;
        this.addRenderableWidget(CycleButton.<MsaaLevel>builder(level -> Component.literal(level.label()))
                .withValues(MsaaLevel.values())
                .withInitialValue(ModernOptSettings.msaa())
                .create(left, y, width, 20, Component.translatable("modernopt.option.msaa"), (button, value) -> {
                    ModernOptConfig.MSAA.set(value);
                    ModernOptSettings.refresh();
                }));

        y += step;
        this.addRenderableWidget(CycleButton.onOffBuilder(ModernOptSettings.ssaa())
                .create(left, y, width, 20, Component.translatable("modernopt.option.ssaa"), (button, value) -> {
                    ModernOptConfig.SSAA.set(value);
                    ModernOptSettings.refresh();
                }));

        y += step;
        this.addRenderableWidget(CycleButton.onOffBuilder(ModernOptSettings.fxaa())
                .create(left, y, width, 20, Component.translatable("modernopt.option.fxaa"), (button, value) -> {
                    ModernOptConfig.FXAA.set(value);
                    ModernOptSettings.refresh();
                }));

        y += step;
        this.addRenderableWidget(CycleButton.onOffBuilder(ModernOptSettings.overlay())
                .create(left, y, width, 20, Component.translatable("modernopt.option.overlay"), (button, value) -> {
                    ModernOptConfig.SHOW_OVERLAY.set(value);
                    ModernOptSettings.refresh();
                }));

        // MSR 2.0 section
        y += step;
        this.addRenderableWidget(CycleButton.onOffBuilder(ModernOptSettings.msrEnabled())
                .create(left, y, width, 20, Component.translatable("modernopt.option.msrEnabled"), (button, value) -> {
                    ModernOptConfig.MSR_ENABLED.set(value);
                    ModernOptSettings.refresh();
                }));

        y += step;
        this.addRenderableWidget(new IntSlider(left, y, width, 20,
                Component.translatable("modernopt.option.msrVarianceScale"), 5, 40,
                (int) (ModernOptSettings.msrVarianceScale() * 10),
                value -> {
                    ModernOptConfig.MSR_VARIANCE_SCALE.set(value / 10.0);
                    ModernOptSettings.refresh();
                },
                value -> (value / 10.0) + "x"));

        y += step;
        this.addRenderableWidget(CycleButton.onOffBuilder(ModernOptSettings.msrEdgeAware())
                .create(left, y, width, 20, Component.translatable("modernopt.option.msrEdgeAware"), (button, value) -> {
                    ModernOptConfig.MSR_EDGE_AWARE.set(value);
                    ModernOptSettings.refresh();
                }));

        y += step;
        this.addRenderableWidget(new IntSlider(left, y, width, 20,
                Component.translatable("modernopt.option.msrSharpness"), 0, 100,
                ModernOptSettings.msrSharpness(),
                value -> {
                    ModernOptConfig.MSR_SHARPNESS.set(value);
                    ModernOptSettings.refresh();
                },
                value -> value + "%"));

        y += step;
        this.addRenderableWidget(new IntSlider(left, y, width, 20,
                Component.translatable("modernopt.option.msrDepthThreshold"), 5, 100,
                (int) (ModernOptSettings.msrDepthThreshold() * 1000),
                value -> {
                    ModernOptConfig.MSR_DEPTH_THRESHOLD.set(value / 1000.0);
                    ModernOptSettings.refresh();
                },
                value -> (value / 1000.0) + ""));

        y += step;
        this.addRenderableWidget(new IntSlider(left, y, width, 20,
                Component.translatable("modernopt.option.msrHistoryWeight"), 50, 95,
                (int) (ModernOptSettings.msrHistoryWeight() * 100),
                value -> {
                    ModernOptConfig.MSR_HISTORY_WEIGHT.set(value / 100.0);
                    ModernOptSettings.refresh();
                },
                value -> (value / 100.0) + ""));

        y += step;
        this.addRenderableWidget(CycleButton.onOffBuilder(ModernOptSettings.overlay())
                .create(left, y, width, 20, Component.translatable("modernopt.option.overlay"), (button, value) -> {
                    ModernOptConfig.SHOW_OVERLAY.set(value);
                    ModernOptSettings.refresh();
                }));

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(left, this.height - 30, width, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        ModernOptConfig.SPEC.save();
        ModernOptSettings.refresh();
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}
