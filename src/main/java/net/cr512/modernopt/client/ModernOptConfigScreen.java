package net.cr512.modernopt.client;

import net.cr512.modernopt.config.ModernOptConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class ModernOptConfigScreen extends Screen {
    private final Screen parent;
    private int page;
    private AbstractSliderButton msaaSlider;

    private static final int PANEL = 0xE8171B25;
    private static final int CARD = 0xF0222835;
    private static final int BORDER = 0xFF343D4D;
    private static final int ACCENT = 0xFF69D6C5;
    private static final int MUTED = 0xFFADB8C8;

    public ModernOptConfigScreen(Screen parent) {
        super(Component.translatable("modernopt.screen.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        cards.clear();
        int center = this.width / 2;
        int tabWidth = 94;
        int tabGap = 6;
        int tabsWidth = tabWidth * 4 + tabGap * 3;
        int left = center - tabsWidth / 2;

        addTab(left, 0, "modernopt.screen.general");
        addTab(left + tabWidth + tabGap, 1, "modernopt.screen.upscalers");
        addTab(left + (tabWidth + tabGap) * 2, 2, "modernopt.screen.aa");
        addTab(left + (tabWidth + tabGap) * 3, 3, "modernopt.screen.dynamic");

        int rowY = 91;
        switch (page) {
            case 0 -> {
                addToggleCard(rowY, "modernopt.option.enabled", "modernopt.option.enabled.desc",
                    ModernOptConfig.ENABLED, "modernopt.value.on", "modernopt.value.off");
                Button benchmark = Button.builder(Component.translatable("modernopt.benchmark.run"),
                        button -> ModernOptBenchmarkScreen.tryStart(this))
                    .bounds(this.width / 2 + 68, rowY + 42, 112, 18)
                    .build();
                addRenderableWidget(benchmark);
            }
            case 1 -> {
                addScaleSlider(rowY, "modernopt.option.scale", "modernopt.option.scale.desc");
                addCycleCard(rowY + 74, "modernopt.option.upscaler", "modernopt.option.upscaler.desc",
                    new String[]{"FSR 1 · EASU + RCAS", "Bilinear", "Nearest", "SoftScale"},
                    () -> ModernOptConfig.UPSCALER_MODE.get(),
                    value -> setValue(ModernOptConfig.UPSCALER_MODE, value));
            }
            case 2 -> {
                addToggleCard(rowY, "modernopt.option.ssaa", "modernopt.option.ssaa.desc",
                    ModernOptConfig.SSAA_ENABLED, "modernopt.value.on", "modernopt.value.off");
                addToggleCard(rowY + 74, "modernopt.option.fxaa", "modernopt.option.fxaa.desc",
                    ModernOptConfig.FXAA_ENABLED, "modernopt.value.on", "modernopt.value.off");
                addMsaaSlider(rowY + 148, "modernopt.option.msaa", "modernopt.option.msaa.desc");
            }
            case 3 -> {
                addToggleCard(rowY, "modernopt.option.dynamic", "modernopt.option.dynamic.desc",
                    ModernOptConfig.DYNAMIC_RESOLUTION_ENABLED, "modernopt.value.on", "modernopt.value.off");
                addIntSlider(rowY + 68, "modernopt.option.targetfps", "modernopt.option.targetfps.desc",
                    30, 540, 10, () -> ModernOptConfig.DYNAMIC_TARGET_FPS.get(),
                    value -> setValue(ModernOptConfig.DYNAMIC_TARGET_FPS, value), " FPS");
                addIntSlider(rowY + 136, "modernopt.option.minScale", "modernopt.option.minScale.desc",
                    25, 100, 5, () -> ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get(),
                    value -> setValue(ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT, value), "%");
                addIntSlider(rowY + 204, "modernopt.option.maxScale", "modernopt.option.maxScale.desc",
                    25, 100, 5, () -> ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get(),
                    value -> setValue(ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT, value), "%");
            }
        }

        Button done = Button.builder(Component.translatable("gui.done"), button -> onClose())
            .bounds(center - 70, this.height - 36, 140, 24).build();
        addRenderableWidget(done);
    }

    private void addTab(int x, int tabPage, String key) {
        ModernOptTabButton tab = new ModernOptTabButton(x, 58, 94, 24,
            Component.translatable(key), page == tabPage, () -> {
                page = tabPage;
                clearWidgets();
                init();
            });
        addRenderableWidget(tab);
    }

    private void addToggleCard(int y, String title, String description, ModConfigSpec.BooleanValue value,
                               String onKey, String offKey) {
        Button control = Button.builder(toggleLabel(value.get(), onKey, offKey), button -> {
            setValue(value, !value.get());
            button.setMessage(toggleLabel(value.get(), onKey, offKey));
            if (value == ModernOptConfig.SSAA_ENABLED && this.msaaSlider != null) {
                this.msaaSlider.active = !value.get();
            }
        }).bounds(this.width / 2 + 68, y + 20, 112, 22).build();
        addRenderableWidget(control);
        cards.add(new Card(y, title, description));
    }

    private void addScaleSlider(int y, String title, String description) {
        AbstractSliderButton slider = new AbstractSliderButton(this.width / 2 + 68, y + 20, 112, 22,
            Component.literal(ModernOptConfig.RENDER_SCALE_PERCENT.get() + "%"),
            (ModernOptConfig.RENDER_SCALE_PERCENT.get() - 25) / 75.0) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal((int)Math.round(25 + value * 75) + "%"));
            }

            @Override
            protected void applyValue() {
                setValue(ModernOptConfig.RENDER_SCALE_PERCENT, (int)Math.round(25 + value * 75));
            }
        };
        addRenderableWidget(slider);
        cards.add(new Card(y, title, description));
    }

    private void addIntSlider(int y, String title, String description, int minimum, int maximum, int step,
                              java.util.function.IntSupplier getter, java.util.function.IntConsumer setter, String suffix) {
        int count = (maximum - minimum) / step;
        int currentIndex = Math.max(0, Math.min(count, Math.round((getter.getAsInt() - minimum) / (float) step)));
        AbstractSliderButton slider = new AbstractSliderButton(this.width / 2 + 68, y + 20, 112, 22,
            Component.literal((minimum + currentIndex * step) + suffix), currentIndex / (double) count) {
            private int selectedIndex() {
                return Math.max(0, Math.min(count, (int) Math.round(this.value * count)));
            }

            @Override
            protected void updateMessage() {
                setMessage(Component.literal((minimum + selectedIndex() * step) + suffix));
            }

            @Override
            protected void applyValue() {
                int index = selectedIndex();
                this.value = index / (double) count;
                updateMessage();
                setter.accept(minimum + index * step);
            }
        };
        addRenderableWidget(slider);
        cards.add(new Card(y, title, description));
    }

    private void addMsaaSlider(int y, String title, String description) {
        int[] samples = {0, 2, 4, 8, 16};
        int current = samplesIndex(ModernOptConfig.MSAA_SAMPLES.get());
        this.msaaSlider = new AbstractSliderButton(this.width / 2 + 68, y + 20, 112, 22,
            Component.literal(samples[current] == 0 ? "Off" : samples[current] + "×"),
            current / (double) (samples.length - 1)) {
            private int selectedIndex() {
                return Math.max(0, Math.min(samples.length - 1,
                    (int) Math.round(this.value * (samples.length - 1))));
            }

            @Override
            protected void updateMessage() {
                int selected = selectedIndex();
                setMessage(Component.literal(samples[selected] == 0 ? "Off" : samples[selected] + "×"));
            }

            @Override
            protected void applyValue() {
                int selected = selectedIndex();
                this.value = selected / (double) (samples.length - 1);
                updateMessage();
                setValue(ModernOptConfig.MSAA_SAMPLES, samples[selected]);
            }
        };
        this.msaaSlider.active = !ModernOptConfig.SSAA_ENABLED.get();
        addRenderableWidget(this.msaaSlider);
        cards.add(new Card(y, title, description));
    }

    private void addCycleCard(int y, String title, String description, String[] options,
                              java.util.function.IntSupplier getter, java.util.function.IntConsumer setter) {
        Button control = Button.builder(Component.literal(options[Math.max(0, Math.min(options.length - 1, getter.getAsInt()))]),
            button -> {
                int next = (getter.getAsInt() + 1) % options.length;
                setter.accept(next);
                button.setMessage(Component.literal(options[next]));
            }).bounds(this.width / 2 + 68, y + 20, 112, 22).build();
        addRenderableWidget(control);
        cards.add(new Card(y, title, description));
    }

    private final java.util.List<Card> cards = new java.util.ArrayList<>();

    private record Card(int y, String titleKey, String descriptionKey) {}

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF0D1119);
        graphics.fillGradient(0, 0, width, 4, ACCENT, 0x0034AFA0);
        int panelLeft = width / 2 - 205;
        int panelRight = width / 2 + 205;
        graphics.fill(panelLeft, 15, panelRight, height - 15, PANEL);
        graphics.fill(panelLeft, 15, panelRight, 16, BORDER);
        graphics.drawCenteredString(font, title, width / 2, 25, 0xFFFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("modernopt.screen.subtitle"),
            width / 2, 40, MUTED);

        for (Card card : cards) {
            int top = card.y;
            graphics.fill(width / 2 - 190, top, width / 2 + 190, top + 62, CARD);
            graphics.fill(width / 2 - 190, top, width / 2 - 187, top + 62, ACCENT);
            graphics.drawString(font, Component.translatable(card.titleKey), width / 2 - 174, top + 13,
                0xFFFFFFFF, false);
            graphics.drawString(font, Component.translatable(card.descriptionKey), width / 2 - 174, top + 34,
                MUTED, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        ModernOptConfig.SPEC.save();
        minecraft.setScreen(parent);
    }

    private static Component toggleLabel(boolean enabled, String onKey, String offKey) {
        return Component.translatable(enabled ? onKey : offKey);
    }

    private static int samplesIndex(int samples) {
        return switch (samples) {
            case 2 -> 1;
            case 4 -> 2;
            case 8 -> 3;
            case 16 -> 4;
            default -> 0;
        };
    }

    private static <T> void setValue(ModConfigSpec.ConfigValue<T> value, T next) {
        value.set(next);
        ModernOptConfig.SPEC.save();
    }
}
