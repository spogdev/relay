package dev.spog.teamlocator.client.hud;

import dev.spog.teamlocator.client.ArmorPiece;
import dev.spog.teamlocator.mixin.client.GuiGraphicsExtractorAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Draws a teammate's armor on a HUD row: the item icon with the same durability bar the inventory
 * puts under it.
 *
 * <p>The durability bar is not reimplemented. A relay {@link ArmorPiece} is rebuilt into a real
 * {@link ItemStack} carrying the reported DAMAGE component, and the bar is then drawn from
 * vanilla's own {@code Item.getBarWidth}/{@code getBarColor} — so it matches the inventory exactly,
 * including the green-to-red gradient and any item that overrides the bar (an elytra's, say).
 *
 * <p>Rebuilding a stack per piece per frame is cheap here: at most four pieces per teammate on a
 * HUD that only lists a handful of people, and {@code ItemStack} construction is a small allocation
 * plus a component set — the same order of work the inventory does every frame.
 */
@Environment(EnvType.CLIENT)
final class ArmorRenderer {
    /** Item icons are 16x16 at scale 1; the HUD draws them smaller to sit inside a text row. */
    static final int ICON_SIZE = 16;
    /** Gap between adjacent armor icons. */
    static final int ICON_GAP = 1;
    private static final int BAR_WIDTH = 13;

    private ArmorRenderer() {
    }

    /**
     * Width in scaled pixels of the armor block for these pieces, so the row can be laid out before
     * anything is drawn.
     */
    static int width(java.util.List<ArmorPiece> pieces, float iconScale) {
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
    static int render(GuiGraphicsExtractor graphics, java.util.List<ArmorPiece> pieces,
                      int x, int centerY, float iconScale) {
        int drawn = Math.round(ICON_SIZE * iconScale);
        int y = centerY - drawn / 2;
        for (ArmorPiece piece : pieces) {
            ItemStack stack = toStack(piece);
            if (stack.isEmpty()) {
                continue; // unknown item (a modded piece we don't have): skip, keep the row intact
            }
            drawItem(graphics, stack, x, y, iconScale);
            drawDurabilityBar(graphics, stack, x, y, drawn, iconScale);
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

    /** Submit the icon through the same render-state path the inventory uses. */
    private static void drawItem(GuiGraphicsExtractor graphics, ItemStack stack,
                                 int x, int y, float iconScale) {
        Minecraft mc = Minecraft.getInstance();
        TrackingItemStackRenderState state = new TrackingItemStackRenderState();
        mc.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI,
                mc.level, mc.player, 0);
        if (state.isEmpty()) {
            return;
        }
        var pose = graphics.pose();
        pose.pushMatrix();
        // GuiItemRenderState draws a fixed 16x16; scale around the icon's origin to shrink it.
        pose.translate(x, y);
        pose.scale(iconScale, iconScale);
        int size = Math.round(ICON_SIZE * iconScale);
        ((GuiGraphicsExtractorAccessor) graphics).relay$guiRenderState().addItem(
                new GuiItemRenderState(new org.joml.Matrix3x2f(pose), state, 0, 0,
                        new ScreenRectangle(x, y, size, size)));
        pose.popMatrix();
    }

    /**
     * The inventory's durability bar: a dark backing with vanilla's colored fill on top, scaled to
     * the icon. Uses {@code Item.getBarWidth}/{@code getBarColor} so it stays identical to the
     * inventory rather than a lookalike.
     */
    private static void drawDurabilityBar(GuiGraphicsExtractor graphics, ItemStack stack,
                                          int x, int y, int drawn, float iconScale) {
        if (!stack.getItem().isBarVisible(stack)) {
            return;
        }
        int barWidth = stack.getItem().getBarWidth(stack);   // 0..13, vanilla's own scale
        int barColor = stack.getItem().getBarColor(stack);

        // Vanilla places the bar at (x+2, y+13) of a 16px icon, 13 long and 2 tall; mirror that
        // geometry through the icon scale so a shrunken icon keeps a proportional bar.
        int barX = x + Math.round(2 * iconScale);
        int barY = y + Math.round(13 * iconScale);
        int fullW = Math.round(BAR_WIDTH * iconScale);
        int fillW = Math.round(barWidth * iconScale);
        int barH = Math.max(1, Math.round(2 * iconScale));

        graphics.fill(barX, barY, barX + fullW, barY + barH, 0xFF000000);
        if (fillW > 0) {
            graphics.fill(barX, barY, barX + fillW, barY + barH, 0xFF000000 | barColor);
        }
    }
}
