package net.cr512.modernopt.client;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/** Minimal integer slider: no step buttons, only the vanilla drag handle. */
final class IntSlider extends AbstractSliderButton {

    private final int minimum;
    private final int maximum;
    private final IntConsumer onChange;
    private final IntFunction<String> valueLabel;
    private final Component baseLabel;

    IntSlider(int x, int y, int width, int height, Component label, int minimum, int maximum,
              int initialValue, IntConsumer onChange, IntFunction<String> valueLabel) {
        super(x, y, width, height, label, normalize(initialValue, minimum, maximum));
        this.minimum = minimum;
        this.maximum = maximum;
        this.onChange = onChange;
        this.valueLabel = valueLabel;
        this.baseLabel = label;
        this.updateMessage();
    }

    private static double normalize(int value, int minimum, int maximum) {
        int span = Math.max(1, maximum - minimum);
        double ratio = (double) (value - minimum) / span;
        return Math.max(0.0D, Math.min(1.0D, ratio));
    }

    int intValue() {
        return this.minimum + (int) Math.round(this.value * (double) (this.maximum - this.minimum));
    }

    @Override
    protected void updateMessage() {
        if (this.valueLabel == null || this.baseLabel == null) {
            return;
        }
        this.setMessage(this.baseLabel.copy().append(": ").append(this.valueLabel.apply(this.intValue())));
    }

    @Override
    protected void applyValue() {
        if (this.onChange != null) {
            this.onChange.accept(this.intValue());
        }
    }
}
