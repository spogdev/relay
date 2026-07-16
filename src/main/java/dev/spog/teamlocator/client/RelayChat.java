package dev.spog.teamlocator.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The mod's feedback channel. Everything the mod tells the user in-game goes to chat rather than
 * the actionbar: the actionbar holds one line for a few seconds, so back-to-back messages (a ping
 * ack landing on a HUD toggle, say) silently overwrote each other and anything missed was gone.
 * Chat keeps the history and the user can scroll it.
 *
 * <p>Messages are colored from the HUD's own palette so the mod reads as one thing: the config's
 * primary color for prose and labels, its secondary color for the values inside them (names,
 * counts) — the same primary/secondary split {@code TeamHud} draws its rows with.
 */
@Environment(EnvType.CLIENT)
public final class RelayChat {
    private RelayChat() {
    }

    /**
     * Post a message to chat in the HUD's primary color.
     *
     * <p>Goes through the chat HUD directly rather than {@code player.sendSystemMessage} so it also
     * works with no player entity — the ping ack can arrive at the title screen.
     */
    public static void send(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) {
            return;
        }
        mc.gui.getChat().addClientSystemMessage(primary(message));
    }

    /** {@code message} in the HUD's primary color; call {@link #value(String)} for the parts inside. */
    public static MutableComponent primary(Component message) {
        return Component.empty().append(message)
                .withStyle(style -> style.withColor(rgb(TeamLocatorClient.CONFIG.hudPrimaryArgb())));
    }

    /**
     * A value to embed in a message — a player name, a count — in the HUD's secondary color, the
     * same way the HUD colors the coordinates inside a row.
     */
    public static MutableComponent value(String text) {
        return Component.literal(text)
                .withStyle(style -> style.withColor(rgb(TeamLocatorClient.CONFIG.hudSecondaryArgb())));
    }

    /** Style colors are plain RGB; the config stores ARGB, whose alpha would corrupt the hue. */
    private static int rgb(int argb) {
        return argb & 0xFFFFFF;
    }
}
