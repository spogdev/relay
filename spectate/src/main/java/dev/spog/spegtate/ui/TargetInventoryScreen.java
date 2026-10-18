package dev.spog.spegtate.ui;

import dev.spog.spegtate.SpectateLang;
import dev.spog.spegtate.SpectateManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The target's inventory, shown to a viewer and not editable.
 *
 * <p>Drawn directly rather than built on {@code HandledScreen}. A handled screen is backed by a
 * real {@code ScreenHandler} with a sync id, and every click it accepts turns into a
 * {@code ClickSlotC2SPacket} aimed at a container the viewer does not have -- so using one would
 * mean constructing a fake handler and then suppressing everything it tries to do. Drawing the
 * slots is less code and cannot interact with anything by construction: there are no slot
 * objects, so there is nothing to click.
 *
 * <p>Laid out like the vanilla player inventory so it reads as the same thing: three rows of
 * nine, a hotbar below with a gap, and armour and offhand set apart on the left.
 *
 * <p>The contents update live. {@link SpectateManager#targetInventory()} returns whatever the
 * last frame carried, and this screen reads it every frame rather than taking a copy, so items
 * moving on the target's screen move here too.
 */
public final class TargetInventoryScreen extends Screen {
    private static final int SLOT = 18;
    private static final int COLUMNS = 9;

    /** Vanilla slot numbering: 0-8 hotbar, 9-35 main, 36-39 armour, 40 offhand. */
    private static final int MAIN_START = 9;
    private static final int MAIN_ROWS = 3;
    private static final int ARMOUR_START = 36;
    private static final int OFFHAND = 40;

    /** Gap between the main grid and the hotbar, as in the vanilla screen. */
    private static final int HOTBAR_GAP = 4;

    private static final int PANEL_PADDING = 8;
    private static final int BACKGROUND = 0xC0101010;
    private static final int SLOT_BACKGROUND = 0x40FFFFFF;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int HINT_COLOR = 0xFFAAAAAA;

    private final Screen parent;
    private final String targetName;

    public TargetInventoryScreen(Screen parent, String targetName) {
        super(Component.literal(targetName));
        this.parent = parent;
        this.targetName = targetName;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY,
                                   float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);

        List<ItemStack> inventory = SpectateManager.get().targetInventory();
        int gridWidth = COLUMNS * SLOT;
        int left = (width - gridWidth) / 2;
        int top = height / 2 - (MAIN_ROWS + 1) * SLOT / 2 - SLOT;

        context.fill(left - PANEL_PADDING, top - SLOT - PANEL_PADDING,
                left + gridWidth + PANEL_PADDING,
                top + MAIN_ROWS * SLOT + HOTBAR_GAP + SLOT + PANEL_PADDING, BACKGROUND);

        context.text(font, SpectateLang.text("inventory.title", targetName),
                left, top - SLOT, TITLE_COLOR, true);

        if (inventory == null) {
            context.text(font, SpectateLang.text("inventory.waiting"),
                    left, top + 4, HINT_COLOR, true);
            return;
        }

        // Main grid, rows 9..35.
        for (int i = 0; i < MAIN_ROWS * COLUMNS; i++) {
            int x = left + (i % COLUMNS) * SLOT;
            int y = top + (i / COLUMNS) * SLOT;
            drawSlot(context, inventory, MAIN_START + i, x, y, mouseX, mouseY);
        }

        // Hotbar, slots 0..8, below the grid with a gap.
        int hotbarY = top + MAIN_ROWS * SLOT + HOTBAR_GAP;
        for (int i = 0; i < COLUMNS; i++) {
            drawSlot(context, inventory, i, left + i * SLOT, hotbarY, mouseX, mouseY);
        }

        // Armour and offhand, set apart on the left so they are not mistaken for inventory.
        int sideX = left - SLOT - PANEL_PADDING * 2;
        for (int i = 0; i < 4; i++) {
            drawSlot(context, inventory, ARMOUR_START + i, sideX, top + i * SLOT, mouseX, mouseY);
        }
        drawSlot(context, inventory, OFFHAND, sideX, top + 4 * SLOT + HOTBAR_GAP, mouseX, mouseY);

        // Tooltip last, so it is drawn over every slot.
        drawHoveredTooltip(context, inventory, left, top, hotbarY, sideX, mouseX, mouseY);
    }

    private void drawSlot(GuiGraphicsExtractor context, List<ItemStack> inventory, int slot,
                          int x, int y, int mouseX, int mouseY) {
        context.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, SLOT_BACKGROUND);
        if (slot >= inventory.size()) {
            return;
        }
        ItemStack stack = inventory.get(slot);
        if (stack.isEmpty()) {
            return;
        }
        context.item(stack, x + 1, y + 1);
        context.itemDecorations(font, stack, x + 1, y + 1);
    }

    /**
     * The tooltip for whichever slot the cursor is over.
     *
     * <p>Recomputed from the same geometry as the drawing above rather than remembered during
     * it, so the two cannot drift apart when the layout changes.
     */
    private void drawHoveredTooltip(GuiGraphicsExtractor context, List<ItemStack> inventory,
                                    int left, int top, int hotbarY, int sideX,
                                    int mouseX, int mouseY) {
        for (int i = 0; i < MAIN_ROWS * COLUMNS; i++) {
            if (hovering(left + (i % COLUMNS) * SLOT, top + (i / COLUMNS) * SLOT, mouseX, mouseY)) {
                tooltip(context, inventory, MAIN_START + i, mouseX, mouseY);
                return;
            }
        }
        for (int i = 0; i < COLUMNS; i++) {
            if (hovering(left + i * SLOT, hotbarY, mouseX, mouseY)) {
                tooltip(context, inventory, i, mouseX, mouseY);
                return;
            }
        }
        for (int i = 0; i < 4; i++) {
            if (hovering(sideX, top + i * SLOT, mouseX, mouseY)) {
                tooltip(context, inventory, ARMOUR_START + i, mouseX, mouseY);
                return;
            }
        }
        if (hovering(sideX, top + 4 * SLOT + HOTBAR_GAP, mouseX, mouseY)) {
            tooltip(context, inventory, OFFHAND, mouseX, mouseY);
        }
    }

    private boolean hovering(int x, int y, int mouseX, int mouseY) {
        return mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT;
    }

    private void tooltip(GuiGraphicsExtractor context, List<ItemStack> inventory, int slot,
                         int mouseX, int mouseY) {
        if (slot >= inventory.size()) {
            return;
        }
        ItemStack stack = inventory.get(slot);
        if (!stack.isEmpty()) {
            context.setTooltipForNextFrame(font, stack, mouseX, mouseY);
        }
    }

    /** Pauses nothing: the stream keeps arriving and the inventory keeps updating behind this. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreenAndShow(parent);
        }
    }
}
