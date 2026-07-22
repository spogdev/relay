package dev.spog.teamlocator.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.List;
import java.util.UUID;

/**
 * Renders relay chat and shared waypoints into the Minecraft chat log.
 *
 * <p>Relay messages are deliberately distinct from server chat: they carry a coloured
 * {@code [Relay Chat]} prefix so it is never ambiguous whether something was said to your trusted
 * group or to everyone on the server. Confusing the two is the failure mode that actually matters —
 * it is how a private message gets read aloud in public.
 */
@Environment(EnvType.CLIENT)
public final class RelayChatMessages {
    /**
     * The prefix's colour. Aqua reads clearly on both the light and dark chat backgrounds and is not
     * used by vanilla system messages, so it does not collide with anything the server sends.
     */
    private static final ChatFormatting PREFIX_COLOR = ChatFormatting.AQUA;
    /** The message body: white, so the text itself stays as readable as ordinary chat. */
    private static final ChatFormatting BODY_COLOR = ChatFormatting.WHITE;
    private static final ChatFormatting WAYPOINT_COLOR = ChatFormatting.GREEN;

    private RelayChatMessages() {
    }

    /**
     * Show an incoming relay chat message as {@code [Relay Chat] <player>: <message>}.
     *
     * <p>The prefix carries a hover listing everyone the relay delivered to, so you can check who
     * could read a message before or after saying something sensitive.
     */
    public static void show(Minecraft mc, UUID sender, String text, List<UUID> recipients) {
        MutableComponent prefix = Component.literal("[Relay Chat]")
                .withStyle(PREFIX_COLOR)
                .withStyle(style -> style.withHoverEvent(
                        new HoverEvent.ShowText(recipientTooltip(mc, recipients))));

        RelayChat.send(prefix
                .append(Component.literal(" <" + PingHandler.displayName(mc, sender) + "> ")
                        .withStyle(PREFIX_COLOR))
                .append(Component.literal(text).withStyle(BODY_COLOR)));
    }

    /**
     * Show a waypoint someone shared, with a click-to-add prompt.
     *
     * <p>Presented as an offer rather than written straight into the receiver's waypoint list:
     * sharing is one-way, so anyone who has added you could otherwise drop entries into your map
     * without consent.
     */
    public static void showWaypoint(Minecraft mc, UUID sender, String name,
                                    int x, int y, int z, String dimension) {
        String who = PingHandler.displayName(mc, sender);
        String command = "/relaywaypoint " + name + " " + x + " " + y + " " + z + " " + dimension;

        MutableComponent line = Component.literal("[Relay Chat]").withStyle(PREFIX_COLOR)
                .append(Component.literal(" " + who + " shared a waypoint: ")
                        .withStyle(WAYPOINT_COLOR))
                .append(Component.literal(name).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (" + x + ", " + y + ", " + z + ") ")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal("[Add]")
                        .withStyle(Style.EMPTY
                                .withColor(ChatFormatting.GREEN)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand(command))
                                .withHoverEvent(new HoverEvent.ShowText(
                                        Component.literal("Add this waypoint to your map")))));
        RelayChat.send(line);
    }

    /** The hover text listing who a message reached. */
    private static Component recipientTooltip(Minecraft mc, List<UUID> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            return Component.literal("Nobody else received this message")
                    .withStyle(ChatFormatting.GRAY);
        }
        MutableComponent tooltip = Component.literal("Received by:\n")
                .withStyle(ChatFormatting.GRAY);
        for (int i = 0; i < recipients.size(); i++) {
            tooltip.append(Component.literal(PingHandler.displayName(mc, recipients.get(i)))
                    .withStyle(ChatFormatting.WHITE));
            if (i < recipients.size() - 1) {
                tooltip.append(Component.literal("\n").withStyle(ChatFormatting.GRAY));
            }
        }
        return tooltip;
    }
}
