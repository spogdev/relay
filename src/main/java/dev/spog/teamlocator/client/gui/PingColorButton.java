package dev.spog.teamlocator.client.gui;

import dev.spog.teamlocator.client.PingPalette;
import dev.spog.teamlocator.client.config.TeamConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Cycles this player's ping colour through the five their UUID produces, with the current one shown
 * as a swatch on the button.
 *
 * <p>The palette is derived rather than stored, so it always matches what the relay independently
 * computes when validating a ping — the two cannot disagree. A player with no profile yet (not
 * signed in) gets a button that simply does nothing rather than a broken one.
 */
public class PingColorButton extends ButtonWidget {
    private static final int SWATCH_SIZE = 10;
    private static final int SWATCH_MARGIN = 6;

    private final TeamConfig config;

    public PingColorButton(int x, int y, int width, int height, TeamConfig config) {
        // The press is handled by the override below, so the callback here is a no-op; extending
        // ButtonWidget rather than PressableWidget gets vanilla's own texture, narration and hover
        // states for free, which is what keeps this looking like every other control on the page.
        super(x, y, width, height, Text.empty(), b -> { }, DEFAULT_NARRATION_SUPPLIER);
        this.config = config;
        updateMessage();
    }

    @Override
    public void onPress(AbstractInput input) {
        List<String> palette = palette();
        if (palette.isEmpty()) {
            return;
        }
        config.pingColorIndex = (config.pingColorIndex + 1) % palette.size();
        config.save();
        updateMessage();
    }

    private void updateMessage() {
        List<String> palette = palette();
        // "Ping Colour: 2/5" — the swatch carries the actual colour, so the number is only there to
        // make it obvious the button cycles and how far through the palette you are.
        setMessage(palette.isEmpty()
                ? Text.translatable("relay.config.ping_color")
                : Text.literal(Text.translatable("relay.config.ping_color").getString()
                        + ": " + (config.pingColorIndex + 1) + "/" + palette.size()));
    }

    /** The five colours for the signed-in account, or empty if there isn't one yet. */
    private static List<String> palette() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getSession() == null || mc.getSession().getUuidOrNull() == null) {
            return List.of();
        }
        return PingPalette.forPlayer(mc.getSession().getUuidOrNull());
    }

    /** The selected colour as opaque ARGB, or white when there is no palette. */
    private int currentArgb() {
        List<String> palette = palette();
        if (palette.isEmpty()) {
            return 0xFFFFFFFF;
        }
        return PingPalette.argb(palette.get(Math.floorMod(config.pingColorIndex, palette.size())));
    }

    @Override
    protected void drawIcon(DrawContext graphics, int mouseX, int mouseY, float delta) {
        // drawIcon is the per-frame hook layered over the button after PressableWidget has already
        // drawn vanilla's own sprite and label, so this only has to add the swatch on top — no need
        // to redraw the background or text as the 26.1 extractContents did.
        // Swatch at the right-hand end: white border, chosen colour inside — the same treatment the
        // HUD colour fields use, so the two read as the same kind of control.
        int x = getX() + width - SWATCH_MARGIN - SWATCH_SIZE;
        int y = getY() + (height - SWATCH_SIZE) / 2;
        graphics.fill(x - 1, y - 1, x + SWATCH_SIZE + 1, y + SWATCH_SIZE + 1, 0xFFFFFFFF);
        graphics.fill(x, y, x + SWATCH_SIZE, y + SWATCH_SIZE, currentArgb());
    }
}
