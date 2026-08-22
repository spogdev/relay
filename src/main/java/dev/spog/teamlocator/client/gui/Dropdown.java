package dev.spog.teamlocator.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A dropdown drawn in the panel style, replacing the CycleButton the settings pages used to use.
 *
 * <p>A cycle button only ever shows one option, so picking a value means clicking through the ones
 * you do not want and knowing when to stop. A dropdown shows the whole set at once and marks the
 * current entry, so choosing is one click and the available options are discoverable.
 *
 * <p>The open list is painted after everything else so it overlaps the rows beneath it, which is
 * why {@link #drawOverlay} is separate from {@link #draw}.
 */
public class Dropdown<T> {
    private static final int ROW_HEIGHT = 16;
    private static final int MAX_VISIBLE = 6;

    private static final int FILL = 0x50161B22;
    private static final int FILL_OPEN = 0xF00E1219;
    private static final int BORDER = 0x70323B47;
    private static final int BORDER_OPEN = 0xA05B6B7D;
    private static final int TEXT = 0xFFE4EAF2;
    private static final int TEXT_HOVER = 0xFFFFFFFF;
    private static final int TEXT_DISABLED = 0xFF6C7683;

    private final List<Entry<T>> entries = new ArrayList<>();
    private final Consumer<T> onPick;

    private int x;
    private int y;
    private int width;
    private boolean open;
    private int scroll;
    /**
     * When false the control paints greyed and ignores clicks, standing in for
     * {@code AbstractWidget.active} — the integrations page disables controls whose mod is absent.
     */
    private boolean active = true;
    /** Hover text, or null for none. Drawn by the screen, which owns tooltip rendering. */
    private Component tooltip;

    public Dropdown(Consumer<T> onPick) {
        this.onPick = onPick;
    }

    /**
     * Replaces the entries.
     *
     * <p>Called every frame from the draw pass, so the scroll offset is only reset when the contents
     * actually change — resetting unconditionally wiped any scrolling before it could render.
     */
    public void setEntries(List<Entry<T>> values) {
        if (entries.equals(values)) {
            return;
        }
        entries.clear();
        entries.addAll(values);
        scroll = 0;
    }

    public void setBounds(int x, int y, int width) {
        this.x = x;
        this.y = y;
        this.width = width;
    }

    public boolean isOpen() {
        return open;
    }

    public void close() {
        open = false;
    }

    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            open = false;
        }
    }

    public boolean isActive() {
        return active;
    }

    public void setTooltip(Component tooltip) {
        this.tooltip = tooltip;
    }

    public Component getTooltip() {
        return tooltip;
    }

    public int getY() {
        return y;
    }

    public static int height(Font font) {
        return font.lineHeight + 8;
    }

    /** True when the cursor is over the closed control, for the screen's tooltip pass. */
    public boolean isHovered(Font font, int mouseX, int mouseY) {
        return contains(mouseX, mouseY, x, y, width, height(font));
    }

    /** The closed control: current value plus a caret. */
    public void draw(GuiGraphicsExtractor graphics, Font font, T current, int mouseX, int mouseY) {
        int boxHeight = height(font);
        boolean hovered = active && contains(mouseX, mouseY, x, y, width, boxHeight);

        frame(graphics, x, y, x + width, y + boxHeight,
                open ? FILL_OPEN : FILL, open || hovered ? BORDER_OPEN : BORDER);

        Entry<T> entry = find(current);
        int textX = x + 6;
        if (entry != null && entry.swatch() != null) {
            drawSwatch(graphics, textX, y + 4, width - (textX - x) - 16, font.lineHeight,
                    entry.swatch());
        } else {
            String label = entry == null ? "-" : entry.label();
            int color = !active ? TEXT_DISABLED : (hovered || open ? TEXT_HOVER : TEXT);
            graphics.text(font, trim(font, label, width - (textX - x) - 16), textX, y + 4, color, false);
        }

        // Caret, pointing the way the list will open.
        int caretX = x + width - 10;
        int caretY = y + boxHeight / 2 - 1;
        for (int i = 0; i < 3; i++) {
            graphics.fill(caretX - i, caretY + (open ? i : -i),
                    caretX + i + 1, caretY + (open ? i : -i) + 1, active ? 0xFF8A93A0 : 0xFF4A525C);
        }
    }

    /** The open list, drawn last so it sits above neighbouring rows. */
    public void drawOverlay(GuiGraphicsExtractor graphics, Font font, T current, int mouseX, int mouseY) {
        if (!open || entries.isEmpty()) {
            return;
        }

        int visible = Math.min(MAX_VISIBLE, entries.size());
        int listHeight = visible * ROW_HEIGHT + 4;
        int listTop = listTop(font);

        frame(graphics, x, listTop, x + width, listTop + listHeight, FILL_OPEN, BORDER_OPEN);

        for (int i = 0; i < visible; i++) {
            Entry<T> entry = entries.get(i + scroll);
            int rowY = listTop + 2 + i * ROW_HEIGHT;
            boolean hovered = contains(mouseX, mouseY, x + 1, rowY, width - 2, ROW_HEIGHT);
            boolean selected = entry.value() == null
                    ? current == null : entry.value().equals(current);

            if (hovered) {
                graphics.fill(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT, 0x5022303F);
            } else if (selected) {
                graphics.fill(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT, 0x3016202B);
            }

            int textX = x + 6;
            if (entry.swatch() != null) {
                drawSwatch(graphics, textX, rowY + 4, width - (textX - x) - 8, font.lineHeight,
                        entry.swatch());
            } else {
                graphics.text(font, trim(font, entry.label(), width - (textX - x) - 8),
                        textX, rowY + 4, hovered ? TEXT_HOVER : TEXT, false);
            }
        }

        // Scrollbar, only when the list is longer than the window.
        if (entries.size() > visible) {
            int trackTop = listTop + 2;
            int trackHeight = visible * ROW_HEIGHT;
            int thumbHeight = Math.max(12, trackHeight * visible / entries.size());
            int thumbY = trackTop + (trackHeight - thumbHeight)
                    * scroll / Math.max(1, entries.size() - visible);
            graphics.fill(x + width - 4, trackTop, x + width - 2, trackTop + trackHeight, 0x40202A38);
            graphics.fill(x + width - 4, thumbY, x + width - 2, thumbY + thumbHeight, 0x90727F8F);
        }
    }

    /** @return true when the click was consumed */
    public boolean click(Font font, double mouseX, double mouseY) {
        if (!active) {
            return false;
        }
        int boxHeight = height(font);
        if (contains((int) mouseX, (int) mouseY, x, y, width, boxHeight)) {
            open = !open;
            return true;
        }

        if (!open) {
            return false;
        }

        int visible = Math.min(MAX_VISIBLE, entries.size());
        int listTop = listTop(font);
        for (int i = 0; i < visible; i++) {
            int rowY = listTop + 2 + i * ROW_HEIGHT;
            if (contains((int) mouseX, (int) mouseY, x + 1, rowY, width - 2, ROW_HEIGHT)) {
                onPick.accept(entries.get(i + scroll).value());
                open = false;
                return true;
            }
        }

        // Clicking away closes without picking.
        open = false;
        return true;
    }

    /**
     * Scrolls the open list.
     *
     * <p>An open dropdown takes the wheel wherever the cursor is: requiring the cursor to be inside
     * the list made scrolling feel broken, because the page moved underneath instead.
     */
    public boolean scroll(double amount) {
        if (!open || entries.size() <= MAX_VISIBLE) {
            return false;
        }
        int max = Math.max(0, entries.size() - MAX_VISIBLE);
        scroll = Math.clamp(scroll - (int) Math.signum(amount), 0, max);
        return true;
    }

    /** Always below the control, so the list opens in a predictable place. */
    private int listTop(Font font) {
        return y + height(font) + 2;
    }

    private Entry<T> find(T value) {
        for (Entry<T> entry : entries) {
            if (entry.value() == null ? value == null : entry.value().equals(value)) {
                return entry;
            }
        }
        return null;
    }

    private static String trim(Font font, String text, int max) {
        String out = text;
        while (font.width(out) > max && out.length() > 1) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /** A colour box filling the row, bordered so a dark colour still reads against the panel. */
    private static void drawSwatch(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int argb) {
        graphics.fill(x, y, x + w, y + h, 0xFF000000);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, argb);
    }

    private static boolean contains(int mouseX, int mouseY, int left, int top, int w, int h) {
        return mouseX >= left && mouseX <= left + w && mouseY >= top && mouseY <= top + h;
    }

    private static void frame(GuiGraphicsExtractor graphics, int left, int top, int right,
                              int bottom, int fill, int border) {
        graphics.fill(left, top, right, bottom, fill);
        graphics.fill(left, top, right, top + 1, border);
        graphics.fill(left, bottom - 1, right, bottom, border);
        graphics.fill(left, top, left + 1, bottom, border);
        graphics.fill(right - 1, top, right, bottom, border);
    }

    /**
     * One row: a value and its label, or -- when {@code swatch} is set -- a colour box drawn in
     * place of the text. Ping colours have no meaningful names, so a numbered list said nothing a
     * swatch does not say better.
     */
    public record Entry<T>(T value, String label, Integer swatch) {
        public Entry(T value, String label) {
            this(value, label, null);
        }
    }
}
