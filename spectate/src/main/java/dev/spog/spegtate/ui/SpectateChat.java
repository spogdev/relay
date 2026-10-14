package dev.spog.spegtate.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The addon's chat output, in the three colours the mod speaks in.
 *
 * <p>Green says an action completed, red says it failed, grey is everything else -- a listing, a
 * stat, a status line. Three fixed meanings rather than per-message choices, so a glance at the
 * colour tells you the outcome without reading the text.
 *
 * <p>Unlike Relay's chat helper these are not configurable. Relay's colours come from the HUD
 * palette because its messages sit beside the HUD; these are success/failure signals, where a
 * user-chosen palette could make a failure look like a success.
 *
 * <p>Goes through the chat HUD directly rather than {@code player.sendMessage} so it also works
 * with no player entity -- a viewer sits at the title screen before a session starts, and an
 * admin command answered there would otherwise be swallowed.
 */
public final class SpectateChat {
    private SpectateChat() {
    }

    /** An action completed. */
    public static void success(Text message) {
        send(colour(message, Formatting.GREEN));
    }

    /** An action failed, or was refused. */
    public static void error(Text message) {
        send(colour(message, Formatting.RED));
    }

    /** Ordinary output: a listing, a stat, a status line. */
    public static void info(Text message) {
        send(colour(message, Formatting.GRAY));
    }

    /** Post a component that already carries its own styling, such as a page link row. */
    public static void raw(Text message) {
        send(message);
    }

    /**
     * Apply a colour without discarding styling the component already has.
     *
     * <p>{@code withStyle(Formatting)} on a component with styled children leaves those children
     * alone, which is what the page-link rows need: the links keep their own colour and
     * click handlers while the surrounding text takes the message colour.
     */
    private static MutableText colour(Text message, Formatting formatting) {
        return Text.empty().append(message).formatted(formatting);
    }

    private static void send(Text message) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null) {
            return;
        }
        mc.inGameHud.getChatHud().addMessage(message);
    }
}
