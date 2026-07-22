package dev.spog.teamlocator.client.hud;

import dev.spog.teamlocator.client.TrackedPos;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

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
    private static final Identifier HEART_SPRITE = Identifier.parse("hud/heart/full");
    /**
     * Drawn a touch larger than the row's text so the heart reads as an icon rather than a glyph,
     * while still not setting the row height (the face and font decide that).
     */
    private static final int ICON_SIZE = 10;
    /** Space between the heart and the number. */
    private static final int ICON_GAP = 3;
    /**
     * Lifts the heart off the text baseline. Centring it on the row's content band leaves it
     * sitting visually low against the digits, because a heart's mass is in its upper half while a
     * digit's is centred — so it is nudged up to look aligned rather than to measure aligned.
     *
     * <p>Held at 1px because the icon is now as tall as the row's content band: at 2 the heart's top
     * edge lands flush against the bottom of the row above, which reads as the two touching.
     */
    private static final int ICON_RAISE = 1;
    /** Space left after the number, before whatever follows (the armor icons). */
    private static final int TRAILING_GAP = 2;

    private HealthRenderer() {
    }

    /** Whether this teammate has health worth drawing. */
    public static boolean has(TrackedPos entry) {
        return entry != null && entry.hasHealth();
    }

    /** Health as it is displayed: whole hearts, halves kept, trailing ".0" dropped. */
    public static String text(float health) {
        // Health is in half-hearts internally (20 = full). Show hearts, since that is the unit
        // players actually think in, and keep a half so "9.5" doesn't round to a misleading "10".
        float hearts = Math.max(0.0f, health) / 2.0f;
        float rounded = Math.round(hearts * 2.0f) / 2.0f;
        return rounded == Math.floor(rounded)
                ? String.valueOf((int) rounded)
                : String.valueOf(rounded);
    }

    /** Total width this occupies, including the icon, its gap, and the number. */
    public static int width(Font font, TrackedPos entry) {
        if (!has(entry)) {
            return 0;
        }
        return ICON_SIZE + ICON_GAP + font.width(text(entry.health())) + TRAILING_GAP;
    }

    /**
     * Draw the heart and number at {@code x}, vertically centred in a row of {@code contentHeight}.
     *
     * @return the width drawn, so the caller can advance past it
     */
    public static int render(GuiGraphicsExtractor graphics, Font font, TrackedPos entry,
                             int x, int y, int contentHeight, int textColor) {
        if (!has(entry)) {
            return 0;
        }
        int iconY = y + (contentHeight - ICON_SIZE) / 2 - ICON_RAISE;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_SPRITE,
                x, iconY, ICON_SIZE, ICON_SIZE);

        String label = text(entry.health());
        int textY = y + (contentHeight - font.lineHeight) / 2;
        // Shadowed, unlike the rest of the row: the number sits right beside a bright red sprite
        // and against whatever the world happens to be behind the HUD, so it needs the outline to
        // stay legible where flat text would not.
        graphics.text(font, label, x + ICON_SIZE + ICON_GAP, textY, textColor, true);
        return ICON_SIZE + ICON_GAP + font.width(label) + TRAILING_GAP;
    }
}
