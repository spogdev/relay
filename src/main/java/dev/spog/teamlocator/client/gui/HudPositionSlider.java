package dev.spog.teamlocator.client.gui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A slider over [0,1] used to set the HUD anchor fraction on one axis. Reports its value live so
 * the caller can persist it and see the HUD move.
 */
public class HudPositionSlider extends AbstractSliderButton {
    private final String label;
    private final Consumer<Double> onChange;

    public HudPositionSlider(int x, int y, int width, int height, String label, double initial,
                             Consumer<Double> onChange) {
        super(x, y, width, height, Component.empty(), initial);
        this.label = label;
        this.onChange = onChange;
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal("%s: %d%%".formatted(label, Math.round(value * 100))));
    }

    @Override
    protected void applyValue() {
        onChange.accept(value);
    }
}
