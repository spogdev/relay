package dev.spog.teamlocator.client.hud;

import dev.spog.teamlocator.client.ArmorPiece;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.item.ItemStack;

import java.util.List;

/**
 * Draws a teammate's armor on a HUD row: the item icon with the same durability bar the inventory
 * puts under it.
 *
 * <p>Rendering goes through {@link DrawContext#item} and
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

    private ArmorRenderer() {
    }

    /**
     * Width in scaled pixels of the armor block for these pieces, so the row can be laid out before
     * anything is drawn.
     */
    static int width(List<ArmorPiece> pieces, float iconScale) {
        if (pieces.isEmpty()) {
            return 0;
        }
        int per = Math.round(ICON_SIZE * iconScale) + ICON_GAP;
        return pieces.size() * per;
    }

    /**
     * Draw the pieces left to right starting at {@code x}, vertically centered on {@code centerY}.
     *
     * @param iconScale shrinks the 16x16 icon to fit the row height
     * @return the x just past the last icon
     */
    static int render(DrawContext graphics, List<ArmorPiece> pieces,
                      int x, int centerY, float iconScale) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int drawn = Math.round(ICON_SIZE * iconScale);
        int y = centerY - drawn / 2;
        for (ArmorPiece piece : pieces) {
            ItemStack stack = toStack(piece);
            if (stack.isEmpty()) {
                continue; // unknown item (a modded piece we don't have): skip, keep the row intact
            }
            var pose = graphics.getMatrices();
            pose.pushMatrix();
            pose.translate(x, y);
            pose.scale(iconScale, iconScale);
            graphics.drawItem(stack, 0, 0);
            // The inventory's decoration pass: durability bar (and cooldown etc.), vanilla's own.
            graphics.drawStackOverlay(mc.textRenderer, stack, 0, 0);
            pose.popMatrix();
            x += drawn + ICON_GAP;
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
