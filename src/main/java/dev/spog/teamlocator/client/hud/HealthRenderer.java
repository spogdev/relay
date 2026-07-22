package dev.spog.teamlocator.client.hud;

import dev.spog.teamlocator.client.TrackedPos;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.util.Identifier;

/**
 * Draws a teammate's health on their HUD row: vanilla's heart icon followed by the number.
 *
 * <p>A single heart plus a figure rather than a row of ten: the HUD is a compact one-line-per-player
 * table, and twenty half-heart sprites per row would dwarf everything else on it. The heart is there
 * to say "this number is health", not to be a health bar.
 */
@Environment(EnvType.CLIENT)
public final class HealthRenderer {
    /** Vanilla's own full-heart sprite, so it matches the player's own HUD exactly. */
    private static final Identifier HEART_SPRITE = Identifier.of("hud/heart/full");
    /**
     * Drawn a touch larger than the row's text so the heart reads as an icon rather than a glyph,
     * while still not setting the row height (the face and font decide that).
     */
    private static final int ICON_SIZE = 10;
    /** Space between the heart and the number: tight, so the two read as one unit. */
    private static final int ICON_GAP = 1;
    /**
     * Lifts the heart off the text baseline. Centring it on the row's content band leaves it
     * sitting visually low against the digits, because a heart's mass is in its upper half while a
     * digit's is centred — so it is nudged up to look aligned rather than to measure aligned.
     *
     * <p>Held at 1px because the icon is now as tall as the row's content band: at 2 the heart's top
     * edge lands flush against the bottom of the row above, which reads as the two touching.
     */
    private static final int ICON_RAISE = 1;
    /**
     * Space before the heart, separating health from the coordinate text that precedes it.
     *
     * <p>Health is its own reading, not a continuation of the coordinates, so it gets a clear break
     * on both sides — wider than the gap between the heart and its own number, which is what makes
     * the pair read as one unit rather than two.
     */
    private static final int LEADING_GAP = 5;
    /** Space left after the number, before whatever follows (the armor icons). */
    private static final int TRAILING_GAP = 6;

    private HealthRenderer() {
    }

    /** Whether this teammate has health worth drawing. */
    public static boolean has(TrackedPos entry) {
        return entry != null && entry.hasHealth();
    }

    /** Health as it is displayed: HP, the raw game value, where a full player reads 20. */
    public static String text(float health) {
        // HP rather than hearts: this is the number damage values are quoted in, so it can be
        // compared against a hit's worth directly. It is always whole, so no fraction to handle —
        // a half-heart is simply 1. Rounded up so a teammate clinging on never reads as dead.
        return String.valueOf((int) Math.ceil(Math.max(0.0f, health)));
    }

    /** Total width this occupies, including the icon, its gap, and the number. */
    public static int width(TextRenderer font, TrackedPos entry) {
        if (!has(entry)) {
            return 0;
        }
        return LEADING_GAP + ICON_SIZE + ICON_GAP + font.getWidth(text(entry.health()))
                + TRAILING_GAP;
    }

    /**
     * Draw the heart and number at {@code x}, vertically centred in a row of {@code contentHeight}.
     *
     * @return the width drawn, so the caller can advance past it
     */
    public static int render(DrawContext graphics, TextRenderer font, TrackedPos entry,
                             int x, int y, int contentHeight, int textColor) {
        if (!has(entry)) {
            return 0;
        }
        int iconX = x + LEADING_GAP;
        int iconY = y + (contentHeight - ICON_SIZE) / 2 - ICON_RAISE;
        graphics.drawGuiTexture(RenderPipelines.GUI_TEXTURED, HEART_SPRITE,
                iconX, iconY, ICON_SIZE, ICON_SIZE);

        String label = text(entry.health());
        int textY = y + (contentHeight - font.fontHeight) / 2;
        // Shadowed, unlike the rest of the row: the number sits right beside a bright red sprite
        // and against whatever the world happens to be behind the HUD, so it needs the outline to
        // stay legible where flat text would not.
        graphics.drawText(font, label, iconX + ICON_SIZE + ICON_GAP, textY, textColor, true);
        return LEADING_GAP + ICON_SIZE + ICON_GAP + font.getWidth(label) + TRAILING_GAP;
    }
}
