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

import java.util.UUID;

/**
 * Decides how a received attack ping is presented. A ping from someone on the same MC server
 * flashes them red on the HUD; a ping from another server (or received while we sit at the menu)
 * shows a toast naming the attacker's server — if the user has cross-server pings enabled. Both
 * play the three-ding alert. Muted players are silenced entirely.
 */
@Environment(EnvType.CLIENT)
public final class PingHandler {
    private static final SystemToast.SystemToastId PING_TOAST = new SystemToast.SystemToastId();

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
        Minecraft mc = Minecraft.getInstance();
        if (fromServer.equals(ourScope)) {
            ClientState.flagAttacked(attacker);
            playPingSound(mc);
            return;
        }
        if (!config.crossServerPings) {
            return;
        }
        mc.execute(() -> SystemToast.add(mc.getToastManager(), PING_TOAST,
                Component.translatable("relay.toast.attacked", displayName(mc, attacker)),
                Component.translatable("relay.toast.server", fromServer)));
        playPingSound(mc);
    }

    /** Cached trust-list name first (works cross-server), then tab list, then a UUID stub. */
    private static String displayName(Minecraft mc, UUID attacker) {
        String name = TeamLocatorClient.CONFIG.nameFor(attacker);
        if (name != null) {
            return name;
        }
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(attacker);
            if (info != null) {
                return info.getProfile().name();
            }
        }
        return attacker.toString().substring(0, 8);
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
