package dev.spog.teamlocator.relay;

import dev.spog.teamlocator.relay.protocol.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trust-aware routing, ported from the mod's former in-game {@code TeamRelay}. Holds each player's
 * outbound trust set ("who may see MY position") and block set, and routes so a position never
 * reaches a viewer the owner didn't share with, and a ping never reaches someone who blocked the
 * sender. Gating lives here — not on the client — so patching a client cannot bypass it.
 *
 * <p>Two differences from the in-game version: (1) position routing is intersected with the
 * MC-server {@link Session#scope() scope}, so players on different servers never see each other's
 * coordinates — pings, by contrast, cross scopes on purpose; (2) positions are pushed by clients
 * and fanned out on arrival, rather than pulled from the world each tick.
 */
public final class RelayRouter {
    /** player -> the viewers that player shares their position with. */
    private final Map<UUID, Set<UUID>> sharesWith = new ConcurrentHashMap<>();
    /** player -> the senders that player has blocked (their pings are dropped). */
    private final Map<UUID, Set<UUID>> blockedBy = new ConcurrentHashMap<>();

    private final SessionRegistry registry;

    public RelayRouter(SessionRegistry registry) {
        this.registry = registry;
    }

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

    /** True if {@code owner} shares their position with {@code viewer}. */
    private boolean shares(UUID owner, UUID viewer) {
        return sharesWith.getOrDefault(owner, Set.of()).contains(viewer);
    }

    /**
     * Fan a player's freshly-reported position out to every viewer, in the same scope, that the
     * player shares with. Called whenever a {@code position-update} arrives.
     */
    public void broadcastPosition(Session owner) {
        UUID ownerId = owner.uuid();
        Messages.PositionSnapshot.Entry entry = new Messages.PositionSnapshot.Entry(
                ownerId.toString(), owner.x, owner.y, owner.z, owner.dimension);

        for (Session viewer : registry.sessionsInScope(owner.scope())) {
            if (viewer.uuid().equals(ownerId)) {
                continue;
            }
            if (shares(ownerId, viewer.uuid())) {
                registry.send(viewer, new Messages.PositionSnapshot(List.of(entry)));
            }
        }
    }

    /**
     * Send a viewer a full snapshot of everyone (in its scope) who currently shares with it. Used
     * right after a viewer authenticates so its HUD populates immediately rather than waiting for
     * each peer's next position tick.
     */
    public void sendInitialSnapshot(Session viewer) {
        UUID viewerId = viewer.uuid();
        List<Messages.PositionSnapshot.Entry> entries = new ArrayList<>();
        for (Session other : registry.sessionsInScope(viewer.scope())) {
            if (other.uuid().equals(viewerId) || !other.hasPosition) {
                continue;
            }
            if (shares(other.uuid(), viewerId)) {
                entries.add(new Messages.PositionSnapshot.Entry(
                        other.uuid().toString(), other.x, other.y, other.z, other.dimension));
            }
        }
        if (!entries.isEmpty()) {
            registry.send(viewer, new Messages.PositionSnapshot(entries));
        }
    }

    /**
     * Fan an attack ping to everyone who mutually trusts the sender and hasn't blocked them.
     * Unlike positions, pings deliberately cross MC-server scopes — a teammate at the main menu or
     * on another server still gets notified. The broadcast carries the attacker's scope so the
     * client can present the two cases differently (and let the user opt out of cross-server ones).
     */
    public void handlePing(Session attacker) {
        UUID attackerId = attacker.uuid();
        Messages.PingBroadcast payload =
                new Messages.PingBroadcast(attackerId.toString(), attacker.scope());

        for (Session viewer : registry.allSessions()) {
            UUID viewerId = viewer.uuid();
            if (viewerId.equals(attackerId)) {
                continue;
            }
            boolean mutual = shares(attackerId, viewerId) && shares(viewerId, attackerId);
            if (!mutual) {
                continue;
            }
            if (blockedBy.getOrDefault(viewerId, Set.of()).contains(attackerId)) {
                continue; // viewer blocked this sender
            }
            registry.send(viewer, payload);
        }
    }
}
