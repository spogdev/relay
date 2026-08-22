package dev.spog.teamlocator.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A flat button drawn in the panel style, so it sits with the cards instead of looking like a stock
 * Minecraft widget.
 *
 * <p>Extends {@link Button} rather than being drawn by hand so it keeps vanilla's focus handling and
 * narration; only the painting is replaced, in {@code extractContents}.
 */
public class PanelButton extends Button {
    private static final int FILL = 0x60161B22;
    private static final int FILL_HOVERED = 0x8022303F;
    private static final int FILL_DISABLED = 0x40101319;
    private static final int BORDER = 0x70323B47;
    private static final int BORDER_HOVERED = 0xA05B6B7D;
    private static final int TEXT = 0xFFE4EAF2;
    private static final int TEXT_DISABLED = 0xFF6C7683;

    private final Consumer<PanelButton> onPress;

    public PanelButton(int x, int y, int width, int height, Component message,
                       Consumer<PanelButton> onPress) {
        // The press is handled by the override below, so the callback here is a no-op.
        super(x, y, width, height, message, b -> { }, DEFAULT_NARRATION);
        this.onPress = onPress;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        onPress.accept(this);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int left = getX();
        int top = getY();
        int right = left + width;
        int bottom = top + height;

        boolean hovered = isHovered() && active;
        int fill = !active ? FILL_DISABLED : (hovered ? FILL_HOVERED : FILL);
        int border = hovered ? BORDER_HOVERED : BORDER;

        graphics.fill(left, top, right, bottom, fill);
        graphics.fill(left, top, right, top + 1, border);
        graphics.fill(left, bottom - 1, right, bottom, border);
        graphics.fill(left, top, left + 1, bottom, border);
        graphics.fill(right - 1, top, right, bottom, border);

        Font font = Minecraft.getInstance().font;
        graphics.text(font, getMessage(),
                left + (width - font.width(getMessage())) / 2,
                top + (height - font.lineHeight) / 2,
                active ? TEXT : TEXT_DISABLED, false);
    }
}
