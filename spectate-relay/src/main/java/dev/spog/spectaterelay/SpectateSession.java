package dev.spog.spectaterelay;

import org.java_websocket.WebSocket;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One connected client of the spectate service.
 *
 * <p>A client is both a possible target (someone a peer may watch) and a possible viewer (someone
 * watching a peer), so one session holds both roles. Which roles are active is the only state worth
 * keeping: the stream itself is forwarded frame by frame and never buffered here.
 */
public final class SpectateSession {
    private final WebSocket conn;

    /** Set once Mojang verification succeeds; null while unauthenticated. */
    private volatile UUID uuid;
    private volatile String profileName;
    private volatile String pendingServerId;

    /** Who this client trusts, as reported by its own client. Used to gate inbound requests. */
    private final Set<UUID> trusts = ConcurrentHashMap.newKeySet();

    /** Viewers currently watching this client. */
    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();

    /** The target this client is watching, or null. One at a time. */
    private volatile UUID watching;

    public SpectateSession(WebSocket conn) {
        this.conn = conn;
    }

    public WebSocket conn() {
        return conn;
    }

    public UUID uuid() {
        return uuid;
    }

    public String profileName() {
        return profileName;
    }

    public boolean isAuthenticated() {
        return uuid != null;
    }

    public void beginChallenge(String serverId, String name) {
        this.pendingServerId = serverId;
        this.profileName = name;
    }

    public String pendingServerId() {
        return pendingServerId;
    }

    public void authenticated(UUID verified) {
        this.uuid = verified;
        this.pendingServerId = null;
    }

    /**
     * Replaces this client's trust set.
     *
     * <p>The relay takes the client's word for who it trusts, which is safe in the direction that
     * matters: a client can only ever shrink or grow the set of people allowed to watch
     * <em>itself</em>. It cannot add itself to anyone else's set, because each session reports only
     * its own, attributed to the UUID this relay verified.
     */
    public void setTrusts(Set<UUID> next) {
        trusts.clear();
        trusts.addAll(next);
    }

    /** True if this client has said {@code other} may watch it. */
    public boolean trusts(UUID other) {
        return trusts.contains(other);
    }

    public void addViewer(UUID viewer) {
        viewers.add(viewer);
    }

    public void removeViewer(UUID viewer) {
        viewers.remove(viewer);
    }

    public Set<UUID> viewers() {
        return Set.copyOf(viewers);
    }

    public boolean hasViewers() {
        return !viewers.isEmpty();
    }

    public UUID watching() {
        return watching;
    }

    public void setWatching(UUID target) {
        this.watching = target;
    }
}
