package dev.spog.teamlocator.client.gui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A percent-labelled slider over an arbitrary [min, max] range. Used for the HUD anchor fractions
 * (0–1) and the HUD size multiplier (0.5–2). Reports its value live so the caller can persist it
 * and see the HUD react.
 */
public class HudPositionSlider extends AbstractSliderButton {
    private final String label;
    private final double min;
    private final double max;
    private final Consumer<Double> onChange;

    /** A [0,1] slider, for the HUD anchor fractions. */
    public HudPositionSlider(int x, int y, int width, int height, String label, double initial,
                             Consumer<Double> onChange) {
        this(x, y, width, height, label, 0.0, 1.0, initial, onChange);
    }

    public HudPositionSlider(int x, int y, int width, int height, String label, double min,
                             double max, double initial, Consumer<Double> onChange) {
        super(x, y, width, height, Component.empty(),
                Math.max(0.0, Math.min(1.0, (initial - min) / (max - min))));
        this.label = label;
        this.min = min;
        this.max = max;
        this.onChange = onChange;
        updateMessage();
    }

    private double scaledValue() {
        return min + value * (max - min);
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal("%s: %d%%".formatted(label, Math.round(scaledValue() * 100))));
    }

    @Override
    protected void applyValue() {
        onChange.accept(scaledValue());
    }
}
