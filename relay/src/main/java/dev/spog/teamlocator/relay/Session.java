package dev.spog.teamlocator.relay;

import dev.spog.teamlocator.relay.protocol.Messages;

import java.util.UUID;

/**
 * One authenticated client connection. Identity ({@link #uuid}) is the UUID the relay verified with
 * Mojang — never a value the client asserted. {@link #scope} is the normalized Minecraft server
 * address the client reported; routing only ever considers sessions in the same scope, so players on
 * different MC servers never see each other even if they trust each other globally.
 */
public final class Session {
    private final org.java_websocket.WebSocket conn;
    private volatile UUID uuid;
    private volatile String scope;
    private volatile boolean authenticated;

    /**
     * The protocol version this client negotiated at hello time, clamped to what the relay speaks
     * (see {@link dev.spog.teamlocator.relay.protocol.Messages#PROTOCOL_VERSION}). Every relay→client
     * message today is safe for any supported version, so nothing reads this yet; it is recorded so
     * that if a future, non-additive change ever needs to shape a down-message per client, the
     * information is already on the session rather than requiring a protocol rework at that point.
     */
    private volatile int protocolVersion = Messages.PROTOCOL_VERSION;

    /** The nonce this connection must satisfy via Mojang joinServer before it is authenticated. */
    private volatile String pendingServerId;
    private volatile String pendingProfileName;
    /** The verified account name, kept after the handshake for display/admin lookups. */
    private volatile String profileName;

    // Last reported position, so a late-joining viewer can be sent an immediate snapshot.
    public volatile double x, y, z;
    /**
     * Last reported health in half-hearts, or -1 when the client has not sent any. Negative rather
     * than 0 as the "unknown" marker, since 0 is a real value meaning a dead player.
     */
    public volatile float health = -1.0f;
    public volatile String dimension = "minecraft:overworld";
    public volatile boolean hasPosition;

    /**
     * Last reported armor, or null while the client shares none. Held per session (like the
     * position) so it can be replayed into a late viewer's initial snapshot — armor is only sent
     * when it changes, so a viewer joining later would otherwise see none until the owner's next
     * equipment change. Immutable once assigned; {@code volatile} publishes it safely to the
     * routing threads.
     */
    public volatile java.util.List<dev.spog.teamlocator.relay.protocol.Messages.ArmorPiece> armor;

    public Session(org.java_websocket.WebSocket conn) {
        this.conn = conn;
    }

    public org.java_websocket.WebSocket conn() {
        return conn;
    }

    public UUID uuid() {
        return uuid;
    }

    public String scope() {
        return scope;
    }

    public boolean authenticated() {
        return authenticated;
    }

    /** The protocol version negotiated with this client (clamped to the relay's own). */
    public int protocolVersion() {
        return protocolVersion;
    }

    public void beginChallenge(String serverId, String profileName, int protocolVersion) {
        this.pendingServerId = serverId;
        this.pendingProfileName = profileName;
        this.protocolVersion = protocolVersion;
    }

    public String pendingServerId() {
        return pendingServerId;
    }

    public String pendingProfileName() {
        return pendingProfileName;
    }

    /**
     * The account name Mojang verified for this session. Retained past the handshake purely for
     * display and for name-based admin lookups ({@code /relay test <player>}); routing and
     * authorization always use {@link #uuid()}, never this.
     */
    public String profileName() {
        return profileName;
    }

    public void authenticate(UUID verifiedUuid, String scope) {
        this.uuid = verifiedUuid;
        this.scope = scope;
        this.profileName = pendingProfileName;
        this.authenticated = true;
        this.pendingServerId = null;
        this.pendingProfileName = null;
    }
}
