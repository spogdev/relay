package dev.spog.teamlocator.client.hud;

import dev.spog.teamlocator.client.ArmorPiece;
import dev.spog.teamlocator.client.config.TeamConfig.DurabilityDisplay;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Draws a teammate's armor on a HUD row: the item icon with the same durability bar the inventory
 * puts under it.
 *
 * <p>Rendering goes through {@link GuiGraphicsExtractor#item} and
 * {@link GuiGraphicsExtractor#itemDecorations} — the exact calls the inventory uses — so the bar's
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
    /** Gap between an icon and its number in {@link DurabilityDisplay#NEXT_TO}. */
    private static final int NUMBER_GAP = 2;

    /**
     * Remaining durability as a figure — the hits a piece has left, which is what the number modes
     * exist to show. A piece with no durability bar (a pumpkin, an unbreakable item) has nothing
     * meaningful to report, so it gets no label rather than a misleading zero.
     */
    private static String durabilityText(ArmorPiece piece) {
        return piece.hasDurability() ? String.valueOf(piece.durabilityLeft()) : "";
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
    static int width(List<ArmorPiece> pieces, float iconScale, DurabilityDisplay mode, Font font) {
        if (pieces.isEmpty()) {
            return 0;
        }
        int drawn = Math.round(ICON_SIZE * iconScale);
        if (font == null || mode == DurabilityDisplay.BAR || mode == DurabilityDisplay.OVER_ICON) {
            // OVER_ICON prints inside the icon's own footprint, so it costs no extra width.
            return pieces.size() * (drawn + ICON_GAP);
        }
        int total = 0;
        for (ArmorPiece piece : pieces) {
            String label = durabilityText(piece);
            total += switch (mode) {
                case NUMBER_ONLY -> font.width(label) + ICON_GAP;
                case NEXT_TO -> drawn + (label.isEmpty() ? 0 : NUMBER_GAP + font.width(label))
                        + ICON_GAP;
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
    static int render(GuiGraphicsExtractor graphics, List<ArmorPiece> pieces,
                      int x, int centerY, float iconScale) {
        return render(graphics, pieces, x, centerY, iconScale, DurabilityDisplay.BAR, null, 0);
    }

    /**
     * Draw the pieces left to right starting at {@code x}, vertically centered on {@code centerY},
     * showing durability in the requested style.
     *
     * <p>Only {@link DurabilityDisplay#BAR} runs vanilla's decoration pass. The number modes
     * deliberately suppress it: a bar and a figure saying the same thing in the same 16px square is
     * noise, and the whole point of asking for a number is that the bar was not precise enough.
     *
     * @return the x just past the last piece
     */
    static int render(GuiGraphicsExtractor graphics, List<ArmorPiece> pieces,
                      int x, int centerY, float iconScale, DurabilityDisplay mode, Font font,
                      int textColor) {
        Minecraft mc = Minecraft.getInstance();
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
                int textY = centerY - font.lineHeight / 2;
                graphics.text(font, label, x, textY, textColor, true);
                x += font.width(label) + ICON_GAP;
                continue;
            }

            var pose = graphics.pose();
            pose.pushMatrix();
            pose.translate(x, y);
            pose.scale(iconScale, iconScale);
            graphics.item(stack, 0, 0);
            if (style == DurabilityDisplay.BAR) {
                // The inventory's decoration pass: durability bar (and cooldown etc.), vanilla's own.
                graphics.itemDecorations(mc.font, stack, 0, 0);
            }
            pose.popMatrix();
            x += drawn;

            if (style == DurabilityDisplay.OVER_ICON && !label.isEmpty()) {
                // Bottom-right of the icon, where vanilla puts a stack count — the position players
                // already read as "a number about this item". Shadowed so it survives the artwork
                // underneath it.
                int textX = x - font.width(label);
                int textY = y + drawn - font.lineHeight;
                graphics.text(font, label, textX, textY, textColor, true);
            } else if (style == DurabilityDisplay.NEXT_TO && !label.isEmpty()) {
                int textY = centerY - font.lineHeight / 2;
                graphics.text(font, label, x + NUMBER_GAP, textY, textColor, true);
                x += NUMBER_GAP + font.width(label);
            }
            x += ICON_GAP;
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
        var item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
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
                stack.set(DataComponents.DAMAGE, Math.min(max, Math.round(used * max)));
            }
        }
        return stack;
    }
}
