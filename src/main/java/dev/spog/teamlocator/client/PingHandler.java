package dev.spog.teamlocator.client;

import dev.spog.teamlocator.client.config.TeamConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Decides how a received attack ping is presented. Every ping shows a toast naming the attacker
 * and their server, wherever we are — in-game, at the menu, on another server — and plays the
 * three-ding alert. A ping from someone on our own MC server additionally flashes them red on the
 * HUD. Cross-server pings can be opted out of; muted players are silenced entirely.
 */
@Environment(EnvType.CLIENT)
public final class PingHandler {
    private static final SystemToast.SystemToastId PING_TOAST = new SystemToast.SystemToastId();
    private static final SystemToast.SystemToastId PING_ACK_TOAST = new SystemToast.SystemToastId();

    private PingHandler() {
    }

    /**
     * @param attacker   verified UUID of the pinging player
     * @param fromServer the attacker's MC-server scope as reported by the relay
     * @param ourScope   the scope this client is currently connected under
     */
    public static void onPing(UUID attacker, String fromServer, String ourScope) {
        TeamConfig config = TeamLocatorClient.CONFIG;
        if (config.mutedPingSet().contains(attacker)) {
            return;
        }
        boolean sameServer = fromServer.equals(ourScope);
        if (!sameServer && !config.crossServerPings) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (sameServer) {
            ClientState.flagAttacked(attacker);
        }
        mc.execute(() -> SystemToast.add(mc.getToastManager(), PING_TOAST,
                Component.translatable("relay.toast.attacked", displayName(mc, attacker)),
                Component.translatable("relay.toast.server", fromServer)));
        playPingSound(mc);
    }

    /**
     * The relay's answer to our own ping: who it was actually delivered to. Shown on the actionbar
     * in-game (the pinger is mid-fight; a toast would be easy to miss there), as a toast otherwise.
     */
    public static void onPingAck(List<UUID> receivers) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            Component message = receivers.isEmpty()
                    ? Component.translatable("relay.ping.no_receivers")
                    : Component.translatable("relay.ping.received_by", receivers.stream()
                            .map(id -> displayName(mc, id))
                            .collect(Collectors.joining(", ")));
            if (mc.player != null) {
                mc.player.sendOverlayMessage(message);
            } else {
                SystemToast.add(mc.getToastManager(), PING_ACK_TOAST, message, null);
            }
        });
    }

    /** Cached trust-list name first (works cross-server), then tab list, then a UUID stub. */
    private static String displayName(Minecraft mc, UUID player) {
        String name = TeamLocatorClient.CONFIG.nameFor(player);
        if (name != null) {
            return name;
        }
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(player);
            if (info != null) {
                return info.getProfile().name();
            }
        }
        return player.toString().substring(0, 8);
    }

    /** Three ascending dings so an attack ping is noticed even without looking at the screen. */
    private static void playPingSound(Minecraft mc) {
        mc.execute(() -> {
            var sounds = mc.getSoundManager();
            sounds.playDelayed(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.0f), 0);
            sounds.playDelayed(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.3f), 4);
            sounds.playDelayed(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.6f), 8);
        });
    }
}
