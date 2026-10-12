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

    /**
     * The Minecraft version this client runs, from its hello.
     *
     * <p>Kept so two clients that cannot decode each other's packets are never paired; see
     * {@code Wire.DENY_VERSION}. Null from a client too old to report it, which is treated as
     * unknown rather than as a match.
     */
    private volatile String gameVersion;
    private volatile String pendingServerId;

    /**
     * Whether this client allows anyone to watch it, as reported by its own client.
     *
     * <p>Defaults to false so a client that has authenticated but not yet sent its consent cannot
     * be watched in the gap. Volatile because it is read by other sessions' request handlers.
     */
    private volatile boolean allowsViewers = false;

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

    public String gameVersion() {
        return gameVersion;
    }

    public void setGameVersion(String version) {
        this.gameVersion = version;
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
     * Records whether this client currently consents to being watched.
     *
     * <p>The relay takes the client's word for this, which is safe in the direction that matters:
     * a client can only ever grant or withhold access to <em>itself</em>. It cannot grant access
     * to anyone else, because each session reports only its own consent, attributed to the UUID
     * this relay verified.
     *
     * <p>This replaced a per-viewer UUID set. The mod is private and everyone holding it has
     * agreed the others may watch them, so the set only ever held "everyone I know" and its real
     * effect was to break whenever the list was incomplete -- a target who had simply never added
     * you was indistinguishable from one refusing. Consent is now the single bit it always was.
     */
    public void setAllowsViewers(boolean next) {
        allowsViewers = next;
    }

    /** True if this client currently allows others to watch it. */
    public boolean allowsViewers() {
        return allowsViewers;
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

    /** Frames forwarded from this session to a viewer, for a periodic progress line. */
    private final java.util.concurrent.atomic.AtomicLong forwarded =
            new java.util.concurrent.atomic.AtomicLong();

    /** Counts one forwarded frame and logs every 100th, so a stalled stream is visible. */
    public void countForwarded() {
        long n = forwarded.incrementAndGet();
        if (n % 100 == 0) {
            org.slf4j.LoggerFactory.getLogger(SpectateSession.class)
                    .info("forwarded {} frames from {}", n, uuid);
        }
    }

    public UUID watching() {
        return watching;
    }

    public void setWatching(UUID target) {
        this.watching = target;
    }
}
