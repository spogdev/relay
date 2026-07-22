package dev.spog.teamlocator.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Formatting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;

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
    private static final Formatting PREFIX_COLOR = Formatting.AQUA;
    /** The message body: white, so the text itself stays as readable as ordinary chat. */
    private static final Formatting BODY_COLOR = Formatting.WHITE;
    private static final Formatting WAYPOINT_COLOR = Formatting.GREEN;

    private RelayChatMessages() {
    }

    /**
     * Show an incoming relay chat message as {@code [Relay Chat] <player>: <message>}.
     *
     * <p>The prefix carries a hover listing everyone the relay delivered to, so you can check who
     * could read a message before or after saying something sensitive.
     */
    public static void show(MinecraftClient mc, UUID sender, String text, List<UUID> recipients) {
        MutableText prefix = Text.literal("[Relay Chat]")
                .formatted(PREFIX_COLOR)
                .styled(style -> style.withHoverEvent(
                        new HoverEvent.ShowText(recipientTooltip(mc, recipients))));

        RelayChat.send(prefix
                .append(Text.literal(" <" + PingHandler.displayName(mc, sender) + "> ")
                        .formatted(PREFIX_COLOR))
                .append(Text.literal(text).formatted(BODY_COLOR)));
    }

    /**
     * Show a waypoint someone shared, with a click-to-add prompt.
     *
     * <p>Presented as an offer rather than written straight into the receiver's waypoint list:
     * sharing is one-way, so anyone who has added you could otherwise drop entries into your map
     * without consent.
     */
    public static void showWaypoint(MinecraftClient mc, UUID sender, String name,
                                    int x, int y, int z, String dimension) {
        String who = PingHandler.displayName(mc, sender);
        // Quote the name so one containing spaces still parses as a single argument.
        String command = "/relaywaypoint \"" + name.replace("\"", "") + "\" "
                + x + " " + y + " " + z + " " + dimension;

        MutableText line = Text.literal("[Relay Chat]").formatted(PREFIX_COLOR)
                .append(Text.literal(" " + who + " shared a waypoint: ")
                        .formatted(WAYPOINT_COLOR))
                .append(Text.literal(name).formatted(Formatting.WHITE))
                .append(Text.literal(" (" + x + ", " + y + ", " + z + ") ")
                        .formatted(Formatting.GRAY))
                .append(Text.literal("[Add]")
                        .fillStyle(Style.EMPTY
                                .withColor(Formatting.GREEN)
                                .withUnderline(true)
                                // SuggestCommand, not RunCommand: RunCommand routes through
                                // sendUnattendedCommand straight to the Minecraft server, which
                                // would both fail (this is a client-only command the server has
                                // never heard of) and publish the coordinates to everyone. Suggest
                                // only calls insertText locally, so the click prefills the chat box
                                // and the user presses enter to run it client-side.
                                .withClickEvent(new ClickEvent.SuggestCommand(command))
                                .withHoverEvent(new HoverEvent.ShowText(
                                        Text.literal(
                                                "Click to fill in the add command, then press "
                                                        + "enter")))));
        RelayChat.send(line);
    }

    /** The hover text listing who a message reached. */
    private static Text recipientTooltip(MinecraftClient mc, List<UUID> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            return Text.literal("Nobody else received this message")
                    .formatted(Formatting.GRAY);
        }
        MutableText tooltip = Text.literal("Received by:\n")
                .formatted(Formatting.GRAY);
        for (int i = 0; i < recipients.size(); i++) {
            tooltip.append(Text.literal(PingHandler.displayName(mc, recipients.get(i)))
                    .formatted(Formatting.WHITE));
            if (i < recipients.size() - 1) {
                tooltip.append(Text.literal("\n").formatted(Formatting.GRAY));
            }
        }
        return tooltip;
    }
}
