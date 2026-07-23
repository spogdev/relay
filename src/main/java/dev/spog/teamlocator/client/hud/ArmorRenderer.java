package dev.spog.teamlocator.client.hud;

import dev.spog.teamlocator.client.ArmorPiece;
import dev.spog.teamlocator.client.config.TeamConfig.DurabilityDisplay;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.item.ItemStack;

import java.util.List;

/**
 * Draws a teammate's armor on a HUD row: the item icon with the same durability bar the inventory
 * puts under it.
 *
 * <p>Rendering goes through {@link DrawContext#drawItem} and
 * {@link DrawContext#drawStackOverlay} — the exact calls the inventory uses — so the bar's
 * geometry and green-to-red gradient are vanilla's own, not a lookalike. The durability itself
 * comes from rebuilding the relay-reported piece into a real {@link ItemStack} carrying the DAMAGE
 * component. Both calls draw a fixed 16px icon; the icon is shrunk to row height by scaling the
 * pose around the icon's origin.
 *
 * <p>Rebuilding a stack per piece per frame is cheap: at most four pieces per teammate on a HUD
 * that lists a handful of people — the same order of work the inventory does every frame.
 */
@Environment(EnvType.CLIENT)
final class ArmorRenderer {
    /** Item icons are 16x16 at scale 1; the HUD draws them smaller to sit inside a text row. */
    static final int ICON_SIZE = 16;
    /** Gap between adjacent armor icons. */
    static final int ICON_GAP = 1;
    /** Gap between an icon and its number in {@link DurabilityDisplay#BOTH}. */
    private static final int NUMBER_GAP = 2;
    /**
     * Gap between one piece and the next in the modes that end in a number.
     *
     * <p>Wider than {@link #ICON_GAP} because that value is tuned for item icons, whose artwork
     * carries its own transparent margin — two icons 1px apart still read as separate. Digits have
     * no such margin, so at 1px the last digit of one piece and the first of the next look like a
     * single number.
     */
    private static final int NUMBER_PIECE_GAP = 6;

    /**
     * Remaining durability as a figure — the hits a piece has left, which is what the number modes
     * exist to show. A piece with no durability bar (a pumpkin, an unbreakable item) has nothing
     * meaningful to report, so it gets no label rather than a misleading zero.
     */
    private static String durabilityText(ArmorPiece piece) {
        return piece.hasDurability() ? String.valueOf(piece.durabilityLeft()) : "";
    }

    /**
     * The colour vanilla's durability bar would be at this piece's damage: hue swept from green at
     * full to red at empty.
     *
     * <p>This is {@code ItemStack.getBarColor}'s own arithmetic rather than an approximation, so a
     * number and a bar showing the same piece are never a different shade — the number modes are
     * meant to be a more precise readout of the bar, not a second opinion on it.
     *
     * <p>Pieces with no durability keep the row's text colour: there is no bar to match, and
     * painting them green would claim a fullness they don't have.
     */
    private static int durabilityColor(ArmorPiece piece, int fallback) {
        if (!piece.hasDurability() || piece.maxDamage() <= 0) {
            return fallback;
        }
        float remaining = Math.clamp(
                1.0f - piece.damage() / (float) piece.maxDamage(), 0.0f, 1.0f);
        // hsvToRgb returns no alpha; the text call needs an opaque colour.
        return 0xFF000000 | MathHelper.hsvToRgb(remaining / 3.0f, 1.0f, 1.0f);
    }

    private ArmorRenderer() {
    }

    /**
     * Width in scaled pixels of the armor block for these pieces, so the row can be laid out before
     * anything is drawn.
     */
    static int width(List<ArmorPiece> pieces, float iconScale) {
        return width(pieces, iconScale, DurabilityDisplay.BAR, null);
    }

    /**
     * Width in scaled pixels of the armor block, accounting for the durability display mode: the
     * number-bearing modes are wider than the bar, and NUMBER_ONLY drops the icon entirely.
     */
    static int width(List<ArmorPiece> pieces, float iconScale, DurabilityDisplay mode, TextRenderer font) {
        if (pieces.isEmpty()) {
            return 0;
        }
        int drawn = Math.round(ICON_SIZE * iconScale);
        if (font == null || mode == DurabilityDisplay.BAR) {
            return pieces.size() * (drawn + ICON_GAP);
        }
        int total = 0;
        for (ArmorPiece piece : pieces) {
            String label = durabilityText(piece);
            total += switch (mode) {
                case NUMBER_ONLY -> font.getWidth(label) + NUMBER_PIECE_GAP;
                case BOTH -> drawn + (label.isEmpty() ? 0 : NUMBER_GAP + font.getWidth(label))
                        + NUMBER_PIECE_GAP;
                default -> drawn + ICON_GAP;
            };
        }
        return total;
    }

    /**
     * Draw the pieces left to right starting at {@code x}, vertically centered on {@code centerY}.
     *
     * @param iconScale shrinks the 16x16 icon to fit the row height
     * @return the x just past the last icon
     */
    static int render(DrawContext graphics, List<ArmorPiece> pieces,
                      int x, int centerY, float iconScale) {
        return render(graphics, pieces, x, centerY, iconScale, DurabilityDisplay.BAR, null, 0);
    }

    /**
     * Draw the pieces left to right starting at {@code x}, vertically centered on {@code centerY},
     * showing durability in the requested style.
     *
     * <p>{@link DurabilityDisplay#BAR} and {@link DurabilityDisplay#BOTH} run vanilla's decoration
     * pass. {@link DurabilityDisplay#NUMBER_ONLY} suppresses it — a bar and a figure in the same
     * 16px square is noise when the whole point of the figure was that the bar wasn't precise enough
     * — whereas BOTH is for players who explicitly want the bar's at-a-glance colour and the exact
     * count side by side.
     *
     * @return the x just past the last piece
     */
    static int render(DrawContext graphics, List<ArmorPiece> pieces,
                      int x, int centerY, float iconScale, DurabilityDisplay mode, TextRenderer font,
                      int textColor) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int drawn = Math.round(ICON_SIZE * iconScale);
        int y = centerY - drawn / 2;
        DurabilityDisplay style = font == null ? DurabilityDisplay.BAR : mode;

        for (ArmorPiece piece : pieces) {
            ItemStack stack = toStack(piece);
            if (stack.isEmpty()) {
                continue; // unknown item (a modded piece we don't have): skip, keep the row intact
            }
            String label = durabilityText(piece);

            if (style == DurabilityDisplay.NUMBER_ONLY) {
                int textY = centerY - font.fontHeight / 2;
                graphics.drawText(font, label, x, textY, durabilityColor(piece, textColor), true);
                x += font.getWidth(label) + NUMBER_PIECE_GAP;
                continue;
            }

            var pose = graphics.getMatrices();
            pose.pushMatrix();
            pose.translate(x, y);
            pose.scale(iconScale, iconScale);
            graphics.drawItem(stack, 0, 0);
            if (style == DurabilityDisplay.BAR || style == DurabilityDisplay.BOTH) {
                // The inventory's decoration pass: durability bar (and cooldown etc.), vanilla's own.
                // BOTH keeps this bar and adds the figure beside the icon below.
                graphics.drawStackOverlay(mc.textRenderer, stack, 0, 0);
            }
            pose.popMatrix();
            x += drawn;

            if (style == DurabilityDisplay.BOTH) {
                if (!label.isEmpty()) {
                    int textY = centerY - font.fontHeight / 2;
                    graphics.drawText(font, label, x + NUMBER_GAP, textY,
                            durabilityColor(piece, textColor), true);
                    x += NUMBER_GAP + font.getWidth(label);
                }
                x += NUMBER_PIECE_GAP;
            } else {
                x += ICON_GAP;
            }
        }
        return x;
    }

    /**
     * Rebuild the sender's item locally. Returns an empty stack for an item this client doesn't
     * know, which the caller skips — a teammate on a modpack we don't have must not break the HUD.
     */
    private static ItemStack toStack(ArmorPiece piece) {
        Identifier id = Identifier.tryParse(piece.item());
        if (id == null) {
            return ItemStack.EMPTY;
        }
        var item = Registries.ITEM.getOptionalValue(id).orElse(null);
        if (item == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item);
        if (piece.hasDurability()) {
            // Set DAMAGE directly rather than setDamageValue: the reported maxDamage is the
            // sender's (enchantments and modded items can change it), so clamp to what this
            // client's copy of the item actually supports and let vanilla's bar math do the rest.
            int max = stack.getMaxDamage();
            if (max > 0) {
                float used = piece.damage() / (float) piece.maxDamage();
                stack.set(DataComponentTypes.DAMAGE, Math.min(max, Math.round(used * max)));
            }
        }
        return stack;
    }
}
