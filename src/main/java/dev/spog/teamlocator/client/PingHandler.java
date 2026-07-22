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
        if (config.hideAllAlerts) {
            return; // master mute: nothing shown, from anyone, anywhere
        }
        if (config.mutedPingSet().contains(attacker)) {
            return;
        }
        boolean sameServer = fromServer.equals(ourScope);
        if (!sameServer && !config.crossServerPings) {
            return;
        }

        // Work out the server line to show. It must never be a raw ip:port — the wire scope is
        // exactly that. For a same-server ping we already know it's here, so show a fixed label. For
        // a cross-server ping, resolve the scope against the servers THIS player knows: an unknown
        // server drops the alert entirely (you are not told the location of a server you have never
        // connected to), and a known one yields the name/address to show in its place.
        final Text serverLine;
        if (sameServer) {
            serverLine = Text.translatable("relay.toast.same_server");
        } else {
            CrossServerResolver.Result resolved = CrossServerResolver.resolve(fromServer);
            if (!resolved.known()) {
                return;
            }
            serverLine = Text.translatable("relay.toast.server", resolved.displayName());
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
                serverLine));
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

    /**
     * Whether an alert from {@code attacker} (scope {@code fromServer}) would be shown on this
     * screen right now — the same gate {@link #onPing} applies, evaluated silently for the relay's
     * availability probes ({@code /available}). Deliberately skips the per-attacker display
     * cooldown: that is a transient anti-spam hold, not availability, and including it would make
     * {@code /available} report "nobody" for several seconds after every real alert.
     *
     * <p>Must stay in lockstep with {@link #onPing}'s early-outs — if a new display gate is added
     * there, add it here, or {@code /available} will overpromise. May do DNS (the known-server
     * resolver); call it off the render thread.
     */
    public static boolean wouldDisplay(UUID attacker, String fromServer, String ourScope) {
        TeamConfig config = TeamLocatorClient.CONFIG;
        if (config.hideAllAlerts) {
            return false; // master mute: /available must not list us as a receiver
        }
        if (config.mutedPingSet().contains(attacker)) {
            return false;
        }
        if (fromServer.equals(ourScope)) {
            return true; // same server: always shown (flash + toast)
        }
        if (!config.crossServerPings) {
            return false;
        }
        return CrossServerResolver.resolve(fromServer).known();
    }

    /** Cached trust-list name first (works cross-server), then tab list, then a UUID stub. */
    public static String displayName(MinecraftClient mc, UUID player) {
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
