package dev.spog.spegtate.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

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
    public static void success(Component message) {
        send(colour(message, ChatFormatting.GREEN));
    }

    /** An action failed, or was refused. */
    public static void error(Component message) {
        send(colour(message, ChatFormatting.RED));
    }

    /** Ordinary output: a listing, a stat, a status line. */
    public static void info(Component message) {
        send(colour(message, ChatFormatting.GRAY));
    }

    /** Post a component that already carries its own styling, such as a page link row. */
    public static void raw(Component message) {
        send(message);
    }

    /**
     * Apply a colour without discarding styling the component already has.
     *
     * <p>{@code withStyle(Formatting)} on a component with styled children leaves those children
     * alone, which is what the page-link rows need: the links keep their own colour and
     * click handlers while the surrounding text takes the message colour.
     */
    private static MutableComponent colour(Component message, ChatFormatting formatting) {
        return Component.empty().append(message).withStyle(formatting);
    }

    private static void send(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) {
            return;
        }
        mc.gui.getChat().addClientSystemMessage(message);
    }
}
