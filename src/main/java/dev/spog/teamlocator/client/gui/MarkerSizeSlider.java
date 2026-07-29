package dev.spog.teamlocator.client.gui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * A plain linear slider over a small integer range, labelled "&lt;label&gt;: &lt;value&gt;&lt;unit&gt;".
 * Used for the in-world marker's size, its idle opacity, and its hide distance.
 *
 * <p>Linear rather than stepped, unlike {@link SecondsSlider}: these ranges are small and every value
 * in them is equally useful, so there is nothing to compress. Reports its value live so the caller
 * can persist and apply it while the slider is being dragged.
 */
public class MarkerSizeSlider extends AbstractSliderButton {
    private final int min;
    private final int max;
    private final String label;
    private final String unit;
    private final IntConsumer onChange;

    public MarkerSizeSlider(int x, int y, int width, int height, String label,
                            int min, int max, int initial, IntConsumer onChange) {
        this(x, y, width, height, label, "px", min, max, initial, onChange);
    }

    /** As above, with an explicit unit suffix for the value ("px", "%", "m", ...). */
    public MarkerSizeSlider(int x, int y, int width, int height, String label, String unit,
                            int min, int max, int initial, IntConsumer onChange) {
        super(x, y, width, height, Component.empty(), fraction(initial, min, max));
        this.min = min;
        this.max = max;
        this.label = label;
        this.unit = unit;
        this.onChange = onChange;
        updateMessage();
    }

    /** Where {@code value} sits in [min, max] as a 0..1 slider position. */
    private static double fraction(int value, int min, int max) {
        if (max <= min) {
            return 0.0;
        }
        return (double) (Math.clamp(value, min, max) - min) / (double) (max - min);
    }

    /** The pixel size the slider currently represents. */
    public int value() {
        return min + (int) Math.round(this.value * (max - min));
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(label + ": " + value() + unit));
    }

    @Override
    protected void applyValue() {
        onChange.accept(value());
    }
}
