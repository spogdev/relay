package dev.spog.teamlocator.client;

import dev.spog.teamlocator.client.config.TeamConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.text.Text;
import net.minecraft.sound.SoundEvents;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Decides how a received attack ping is presented. Every ping shows a toast naming the attacker
 * and their server, wherever we are — in-game, at the menu, on another server — and plays the
 * configured alert sound (the new alarm by default, or the three-ding noteblock chord). A ping
 * from someone on our own MC server additionally flashes them red on the
 * HUD. Cross-server pings can be opted out of; muted players are silenced entirely, and repeat
 * pings from the same attacker are hidden for the configured per-player display cooldown.
 */
@Environment(EnvType.CLIENT)
public final class PingHandler {
    private static final SystemToast.Type PING_TOAST = new SystemToast.Type();

    /** When each attacker's ping was last shown, for the anti-spam display cooldown. */
    private static final Map<UUID, Long> lastDisplayedAt = new ConcurrentHashMap<>();

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
        // Anti-spam: the ping is still received, just not shown while this attacker pinged
        // within the last pingCooldownSeconds.
        long now = System.currentTimeMillis();
        long cooldownMs = config.pingCooldownSeconds * 1000L;
        Long last = lastDisplayedAt.get(attacker);
        if (last != null && now - last < cooldownMs) {
            return;
        }
        lastDisplayedAt.put(attacker, now);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (sameServer) {
            ClientState.flagAttacked(attacker);
        }
        mc.execute(() -> SystemToast.add(mc.getToastManager(), PING_TOAST,
                Text.translatable("relay.toast.attacked", displayName(mc, attacker)),
                Text.translatable("relay.toast.server", fromServer)));
        playPingSound(mc);
    }

    /**
     * The relay's answer to our own ping: who it was actually delivered to. Goes to chat, which —
     * unlike the actionbar it used to use — persists, so a pinger mid-fight can still read who
     * answered once the fight is over. Chat is drawn at the title screen too, so this no longer
     * needs the toast fallback for the no-player case.
     */
    public static void onPingAck(List<UUID> receivers) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> RelayChat.send(receivers.isEmpty()
                ? Text.translatable("relay.ping.no_receivers")
                : Text.translatable("relay.ping.received_by", RelayChat.value(receivers.stream()
                        .map(id -> displayName(mc, id))
                        .collect(Collectors.joining(", "))))));
    }

    /** Cached trust-list name first (works cross-server), then tab list, then a UUID stub. */
    private static String displayName(MinecraftClient mc, UUID player) {
        String name = TeamLocatorClient.CONFIG.nameFor(player);
        if (name != null) {
            return name;
        }
        if (mc.getNetworkHandler() != null) {
            PlayerListEntry info = mc.getNetworkHandler().getPlayerListEntry(player);
            if (info != null) {
                return info.getProfile().name();
            }
        }
        return player.toString().substring(0, 8);
    }

    /**
     * Play the configured alert sound so an attack ping is noticed even without looking at the
     * screen: the new alarm plays once (the default), or the three ascending noteblock dings.
     */
    private static void playPingSound(MinecraftClient mc) {
        mc.execute(() -> {
            var sounds = mc.getSoundManager();
            if (TeamLocatorClient.CONFIG.alertSound == TeamConfig.AlertSound.NOTEBLOCKS) {
                sounds.play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.0f), 0);
                sounds.play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.3f), 4);
                sounds.play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.6f), 8);
            } else {
                sounds.play(PositionedSoundInstance.ui(RelaySounds.ALARM, 1.0f), 0);
            }
        });
    }
}
