package dev.spog.teamlocator.client.compat.xaero;

import dev.spog.teamlocator.client.RelayChat;
import dev.spog.teamlocator.client.TeamLocatorClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;
import xaero.map.mods.gui.Waypoint;

/**
 * The "Share to trusted" entry in a world-map waypoint's right-click menu.
 *
 * <p>Sharing is one-way: this goes to everyone <em>you</em> share with, whether or not they have
 * added you back, because showing someone a location is a gift rather than a conversation. They
 * receive it as an offer in chat and choose whether to add it — see
 * {@code RelayChatMessages.showWaypoint}.
 *
 * <p>Lives outside the mixin so the Xaero types it touches are only resolved when the mod is
 * present, and so the behaviour is plain code rather than bytecode-injected logic.
 */
@Environment(EnvType.CLIENT)
public final class WaypointShareOption extends RightClickOption {
    private final Waypoint waypoint;

    public WaypointShareOption(Waypoint waypoint, IRightClickableElement element, int index) {
        super("Share to trusted", index, element);
        this.waypoint = waypoint;
    }

    @Override
    public void onAction(Screen screen) {
        if (!TeamLocatorClient.RELAY.isReady()) {
            RelayChat.send(Component.literal("Not connected to the relay — waypoint not shared.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        String dimension = mc.level != null
                ? mc.level.dimension().identifier().toString()
                : "minecraft:overworld";
        String name = waypoint.getName() == null ? "Waypoint" : waypoint.getName();

        TeamLocatorClient.RELAY.shareWaypoint(
                name, waypoint.getX(), waypoint.getY(), waypoint.getZ(), dimension);
        RelayChat.send(Component.literal("Shared waypoint: ")
                .withStyle(ChatFormatting.GREEN)
                .append(Component.literal(name).withStyle(ChatFormatting.WHITE)));
    }
}
