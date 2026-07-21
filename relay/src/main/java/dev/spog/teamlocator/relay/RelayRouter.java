package dev.spog.teamlocator.relay;

import dev.spog.teamlocator.relay.protocol.Messages;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
    /**
     * player -> the peers that player exchanges attack pings with. Kept separate from
     * {@link #sharesWith}: the sharing set is emptied by the share toggle and scoped to the active
     * per-server list, while alert trust must survive both so pings work from the menu or another
     * server. An old client sends no alert set; the server falls this back to its sharing set.
     */
    private final Map<UUID, Set<UUID>> alertsWith = new ConcurrentHashMap<>();
    /** player -> the senders that player has blocked (their pings are dropped). */
    private final Map<UUID, Set<UUID>> blockedBy = new ConcurrentHashMap<>();

    private final SessionRegistry registry;

    public RelayRouter(SessionRegistry registry) {
        this.registry = registry;
    }

    public void setSharing(UUID player, Set<UUID> viewers) {
        sharesWith.put(player, Set.copyOf(viewers));
    }

    public void setAlerts(UUID player, Set<UUID> peers) {
        alertsWith.put(player, Set.copyOf(peers));
    }

    public void setBlocked(UUID player, Set<UUID> blocked) {
        blockedBy.put(player, Set.copyOf(blocked));
    }

    public void remove(UUID player) {
        sharesWith.remove(player);
        alertsWith.remove(player);
        blockedBy.remove(player);
    }

    /** True if {@code owner} shares their position with {@code viewer}. */
    private boolean shares(UUID owner, UUID viewer) {
        return sharesWith.getOrDefault(owner, Set.of()).contains(viewer);
    }

    /** True if {@code owner} extends alert trust to {@code peer} (gates ping delivery). */
    private boolean alerts(UUID owner, UUID peer) {
        return alertsWith.getOrDefault(owner, Set.of()).contains(peer);
    }

    /**
     * Fan a player's freshly-reported position out to every viewer, in the same scope, that the
     * player shares with. Called whenever a {@code position-update} arrives.
     */
    public void broadcastPosition(Session owner) {
        UUID ownerId = owner.uuid();
        Messages.PositionSnapshot.Entry entry = new Messages.PositionSnapshot.Entry(
                ownerId.toString(), owner.x, owner.y, owner.z, owner.dimension, owner.armor);

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
     * Push a player's changed armor to everyone who may see them. Reuses the position snapshot —
     * the entry carries both — rather than adding a second message type: armor only changes on
     * equip/damage, so the extra position payload riding along is negligible, and the client has
     * one code path that keeps a teammate's position and armor consistent.
     *
     * <p>Gated by the same {@link #shares} check as positions: sharing armor with someone you do
     * not share your position with is not a thing the mod offers, so armor can never leak wider
     * than coordinates already do.
     */
    public void broadcastArmor(Session owner) {
        if (!owner.hasPosition) {
            // Nothing to attach the armor to yet; the next position update carries it.
            return;
        }
        broadcastPosition(owner);
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
                        other.uuid().toString(), other.x, other.y, other.z, other.dimension,
                        other.armor));
            }
        }
        if (!entries.isEmpty()) {
            registry.send(viewer, new Messages.PositionSnapshot(entries));
        }
    }

    /**
     * Fan a location ping to everyone in the sender's scope who mutually alert-trusts them.
     *
     * <p>Scope-limited, unlike attack alerts: a ping marks a point in a world, and the same
     * coordinates on a different Minecraft server would mark an unrelated place.
     *
     * <p><b>The colour is not taken on trust.</b> A client may only use one of the five colours its
     * own UUID produces; anything else is replaced with its first. An administrator's override wins
     * over both, so a player whose colour was overridden cannot escape it by patching their client.
     * Blocked senders are filtered as for attack pings, so muting someone hides their pings too.
     *
     * @param requestedColor the colour the client asked for, which may be anything at all
     * @param overrideColor  an administrator's override for this player, or null
     */
    public void broadcastMapPing(Session sender, double x, double y, double z, String dimension,
                                 String requestedColor, String overrideColor) {
        UUID senderId = sender.uuid();
        String color = resolvePingColor(senderId, requestedColor, overrideColor);
        Messages.MapPingBroadcast payload = new Messages.MapPingBroadcast(
                senderId.toString(), x, y, z, dimension, color);

        // Echo to the sender first. The client shows its own ping immediately on keypress so the
        // keybind feels responsive, but it derives that colour from its own palette and cannot know
        // about an administrator's override — so the placer saw their own colour while everyone else
        // saw the forced one. This echo carries the resolved colour and corrects it, and because it
        // is per ping rather than at auth, an override applied mid-session takes effect at once.
        registry.send(sender, payload);

        for (Session viewer : registry.sessionsInScope(sender.scope())) {
            UUID viewerId = viewer.uuid();
            if (viewerId.equals(senderId)) {
                continue; // echoed above, unconditionally — trust/mute rules don't apply to yourself
            }
            boolean mutual = alerts(senderId, viewerId) && alerts(viewerId, senderId);
            if (!mutual) {
                continue;
            }
            if (blockedBy.getOrDefault(viewerId, Set.of()).contains(senderId)) {
                continue; // muted: their pings are suppressed along with their alerts
            }
            registry.send(viewer, payload);
        }
    }

    /**
     * The colour a ping actually carries: an administrator's override if one exists, else the
     * client's choice when it is genuinely one of that player's five, else that player's first
     * colour. Never returns a value the sender simply made up.
     */
    static String resolvePingColor(UUID sender, String requested, String override) {
        String normalizedOverride = dev.spog.teamlocator.relay.protocol.PingColors.normalize(override);
        if (normalizedOverride != null) {
            return normalizedOverride;
        }
        if (dev.spog.teamlocator.relay.protocol.PingColors.isValidFor(sender, requested)) {
            return requested.trim().toLowerCase(java.util.Locale.ROOT);
        }
        return dev.spog.teamlocator.relay.protocol.PingColors.forPlayer(sender, 0);
    }

    /**
     * Fan an attack ping to everyone who mutually alert-trusts the sender and hasn't blocked them.
     * Unlike positions, pings deliberately cross MC-server scopes — a teammate at the main menu or
     * on another server still gets notified. The broadcast carries the attacker's scope so the
     * client can present the two cases differently (and let the user opt out of cross-server ones).
     * The attacker gets a {@link Messages.PingAck} back listing who was actually reached.
     */
    public void handlePing(Session attacker) {
        UUID attackerId = attacker.uuid();
        Messages.PingBroadcast payload =
                new Messages.PingBroadcast(attackerId.toString(), attacker.scope());

        List<String> receivers = new ArrayList<>();
        for (Session viewer : registry.allSessions()) {
            if (!wouldDeliverPing(attackerId, viewer)) {
                continue;
            }
            registry.send(viewer, payload);
            receivers.add(viewer.uuid().toString());
        }
        registry.send(attacker, new Messages.PingAck(receivers));
    }

    /** The relay-side ping gate: mutual alert trust, not self, and the viewer hasn't blocked us. */
    private boolean wouldDeliverPing(UUID attackerId, Session viewer) {
        UUID viewerId = viewer.uuid();
        if (viewerId.equals(attackerId)) {
            return false;
        }
        if (!(alerts(attackerId, viewerId) && alerts(viewerId, attackerId))) {
            return false;
        }
        return !blockedBy.getOrDefault(viewerId, Set.of()).contains(attackerId);
    }

    // ---- availability probes (/available) ----

    /**
     * How long the relay waits for probe answers before sending what it has. One round trip plus
     * the receiver's local evaluation (which may do DNS) fits comfortably; a peer slower than this
     * is dropped from the result rather than holding the requester's answer hostage.
     */
    private static final long PROBE_TIMEOUT_MS = 1500;

    /** One in-flight availability query: who still owes an answer, and who said yes so far. */
    private static final class Probe {
        final Session requester;
        final Set<UUID> awaiting;
        final List<String> available = new ArrayList<>();
        volatile ScheduledFuture<?> timeout;

        Probe(Session requester, Set<UUID> awaiting) {
            this.requester = requester;
            this.awaiting = awaiting;
        }
    }

    private final Map<String, Probe> probes = new ConcurrentHashMap<>();

    /** Daemon timer for probe windows; one thread is plenty at this message rate. */
    private final ScheduledExecutorService probeTimer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "relay-probe-timeout");
                t.setDaemon(true);
                return t;
            });

    /**
     * Answer "who would see my alert?" for the requester. Candidates are exactly the sessions a
     * real ping would be delivered to ({@link #wouldDeliverPing}), minus pre-v3 clients — they
     * would show a real alert, but they cannot answer a probe, and the command promises only
     * players <em>verified</em> to see it. Each candidate evaluates its own display gate (mute,
     * cross-server toggle, known-server check) and replies; the aggregate goes back to the
     * requester when everyone answered or the window closes.
     */
    public void handleAvailabilityQuery(Session requester) {
        UUID requesterId = requester.uuid();
        List<Session> targets = new ArrayList<>();
        for (Session viewer : registry.allSessions()) {
            if (wouldDeliverPing(requesterId, viewer)
                    && viewer.protocolVersion() >= Messages.AVAILABILITY_MIN_VERSION) {
                targets.add(viewer);
            }
        }
        if (targets.isEmpty()) {
            registry.send(requester, new Messages.AvailabilityResult(List.of()));
            return;
        }

        String probeId = UUID.randomUUID().toString();
        Set<UUID> awaiting = new HashSet<>();
        for (Session t : targets) {
            awaiting.add(t.uuid());
        }
        Probe probe = new Probe(requester, awaiting);
        probes.put(probeId, probe);
        probe.timeout = probeTimer.schedule(() -> finishProbe(probeId),
                PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        Messages.AvailabilityProbe payload =
                new Messages.AvailabilityProbe(probeId, requesterId.toString(), requester.scope());
        for (Session t : targets) {
            registry.send(t, payload);
        }
    }

    /** A probed client's verdict. Ignores stale, duplicate, or never-asked responders. */
    public void handleAvailabilityResponse(Session responder, String probeId, boolean available) {
        Probe probe = probes.get(probeId);
        if (probe == null) {
            return; // already finished (timeout beat the answer) or never existed
        }
        boolean done;
        synchronized (probe) {
            if (!probe.awaiting.remove(responder.uuid())) {
                return; // not asked, or answered twice
            }
            if (available) {
                probe.available.add(responder.uuid().toString());
            }
            done = probe.awaiting.isEmpty();
        }
        if (done) {
            finishProbe(probeId);
        }
    }

    /** Send the aggregate exactly once — the map remove decides the winner between answer and timeout. */
    private void finishProbe(String probeId) {
        Probe probe = probes.remove(probeId);
        if (probe == null) {
            return;
        }
        ScheduledFuture<?> timeout = probe.timeout;
        if (timeout != null) {
            timeout.cancel(false);
        }
        List<String> result;
        synchronized (probe) {
            result = List.copyOf(probe.available);
        }
        registry.send(probe.requester, new Messages.AvailabilityResult(result));
    }

    /** Stop the probe timer; pending probes die with the process. */
    public void shutdown() {
        probeTimer.shutdownNow();
    }
}
