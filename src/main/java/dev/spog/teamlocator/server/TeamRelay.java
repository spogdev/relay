package dev.spog.teamlocator.server;

import dev.spog.teamlocator.net.PingBroadcastPayload;
import dev.spog.teamlocator.net.PositionSnapshotPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-authoritative routing of positions and attack pings. Holds each player's uploaded
 * outbound trust set ("who may see MY position") and block set, and uses them to route so that a
 * position never reaches a viewer the owner did not trust, and a ping never reaches someone who
 * blocked the sender. Trust gating lives here — not on the client — so it cannot be bypassed.
 */
public final class TeamRelay {
    /** player -> the viewers that player shares their position with. */
    private final Map<UUID, Set<UUID>> sharesWith = new ConcurrentHashMap<>();
    /** player -> the senders that player has blocked (their pings are dropped). */
    private final Map<UUID, Set<UUID>> blockedBy = new ConcurrentHashMap<>();

    /** How often (in server ticks) to broadcast position snapshots. 4 ticks = 5 Hz. */
    private static final int BROADCAST_INTERVAL = 4;
    private int tickCounter;

    public void setSharing(UUID player, Set<UUID> viewers) {
        sharesWith.put(player, Set.copyOf(viewers));
    }

    public void setBlocked(UUID player, Set<UUID> blocked) {
        blockedBy.put(player, Set.copyOf(blocked));
    }

    public void remove(UUID player) {
        sharesWith.remove(player);
        blockedBy.remove(player);
    }

    /** Fan an attack ping out to everyone who mutually trusts the sender and hasn't blocked them. */
    public void handlePing(MinecraftServer server, ServerPlayer attacker) {
        UUID attackerId = attacker.getUUID();
        Set<UUID> attackerShares = sharesWith.getOrDefault(attackerId, Set.of());
        PingBroadcastPayload payload = new PingBroadcastPayload(attackerId);

        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            UUID viewerId = viewer.getUUID();
            if (viewerId.equals(attackerId)) {
                continue;
            }
            boolean mutual = attackerShares.contains(viewerId)
                    && sharesWith.getOrDefault(viewerId, Set.of()).contains(attackerId);
            if (!mutual) {
                continue;
            }
            if (blockedBy.getOrDefault(viewerId, Set.of()).contains(attackerId)) {
                continue; // viewer blocked this sender
            }
            if (ServerPlayNetworking.canSend(viewer, PingBroadcastPayload.ID)) {
                ServerPlayNetworking.send(viewer, payload);
            }
        }
    }

    /** Called every server tick; emits position snapshots every {@link #BROADCAST_INTERVAL} ticks. */
    public void tick(MinecraftServer server) {
        if (++tickCounter < BROADCAST_INTERVAL) {
            return;
        }
        tickCounter = 0;

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }

        for (ServerPlayer viewer : players) {
            UUID viewerId = viewer.getUUID();
            if (!ServerPlayNetworking.canSend(viewer, PositionSnapshotPayload.ID)) {
                continue; // vanilla client without the mod
            }
            List<PositionSnapshotPayload.PlayerPos> entries = new ArrayList<>();
            for (ServerPlayer shared : players) {
                if (shared.getUUID().equals(viewerId)) {
                    continue;
                }
                // Include `shared` only if `shared` chose to share with this viewer.
                if (sharesWith.getOrDefault(shared.getUUID(), Set.of()).contains(viewerId)) {
                    entries.add(new PositionSnapshotPayload.PlayerPos(
                            shared.getUUID(),
                            shared.getX(), shared.getY(), shared.getZ(),
                            shared.level().dimension().identifier()));
                }
            }
            ServerPlayNetworking.send(viewer, new PositionSnapshotPayload(entries));
        }
    }
}
