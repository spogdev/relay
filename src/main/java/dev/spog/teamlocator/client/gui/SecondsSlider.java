package dev.spog.teamlocator.client.gui;

import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;

/**
 * A seconds-labelled slider over an integer [min, max] range, displayed as "&lt;label&gt;: &lt;n&gt;s".
 * Reports its value live so the caller can persist it immediately.
 */
public class SecondsSlider extends SliderWidget {
    private final String label;
    private final int min;
    private final int max;
    private final IntConsumer onChange;

    public SecondsSlider(int x, int y, int width, int height, String label, int min, int max,
                         int initial, IntConsumer onChange) {
        super(x, y, width, height, Text.empty(),
                Math.max(0.0, Math.min(1.0, (initial - min) / (double) (max - min))));
        this.label = label;
        this.min = min;
        this.max = max;
        this.onChange = onChange;
        updateMessage();
    }

    private int seconds() {
        return (int) Math.round(min + value * (max - min));
    }

    @Override
    protected void updateMessage() {
        setMessage(Text.literal("%s: %ds".formatted(label, seconds())));
    }

    @Override
    protected void applyValue() {
        onChange.accept(seconds());
    }
}
