package dev.spog.teamlocator.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A page tab, drawn flat rather than as a vanilla button.
 *
 * <p>Vanilla's button texture is what every setting on the page below already uses, so tabs built
 * from it read as just two more settings. These instead borrow the convention tabs use elsewhere:
 * the selected tab shares the page's background and is joined to it by the absence of a divider,
 * with an accent underline marking it; unselected tabs are recessed and dimmed, and lift on hover.
 * The result reads as "these two are a different kind of control" without any texture of its own.
 */
@Environment(EnvType.CLIENT)
public class TabButton extends AbstractWidget {
    private static final int ACCENT = 0xFF4C9EFF;
    private static final int SELECTED_BG = 0xFF2A2A2A;
    private static final int UNSELECTED_BG = 0xFF141414;
    private static final int HOVER_BG = 0xFF1F1F1F;
    private static final int BORDER = 0xFF000000;
    private static final int TEXT_SELECTED = 0xFFFFFFFF;
    private static final int TEXT_UNSELECTED = 0xFF909090;
    /** Thickness of the accent underline on the selected tab. */
    private static final int UNDERLINE_H = 2;

    private final boolean selected;
    private final Runnable onSelect;

    public TabButton(int x, int y, int width, int height, Component label, boolean selected,
                     Runnable onSelect) {
        super(x, y, width, height, label);
        this.selected = selected;
        this.onSelect = onSelect;
        // The selected tab is not a control — it's a label for where you already are.
        this.active = !selected;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                            float delta) {
        int x = getX();
        int y = getY();
        int right = x + this.width;
        int bottom = y + this.height;

        int background = selected ? SELECTED_BG : (isHovered() ? HOVER_BG : UNSELECTED_BG);
        graphics.fill(x, y, right, bottom, background);

        // Outline the top and sides only: leaving the bottom edge open is what visually joins the
        // selected tab to the page beneath it. Unselected tabs keep their bottom edge, so they read
        // as sitting behind the page rather than opening onto it.
        graphics.fill(x, y, right, y + 1, BORDER);
        graphics.fill(x, y, x + 1, bottom, BORDER);
        graphics.fill(right - 1, y, right, bottom, BORDER);
        if (!selected) {
            graphics.fill(x, bottom - 1, right, bottom, BORDER);
        } else {
            graphics.fill(x + 1, bottom - UNDERLINE_H, right - 1, bottom, ACCENT);
        }

        graphics.centeredText(Minecraft.getInstance().font, getMessage(), x + this.width / 2,
                y + (this.height - 8) / 2, selected ? TEXT_SELECTED : TEXT_UNSELECTED);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubled) {
        onSelect.run();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }
}
