package dev.spog.teamlocator.relay;

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

    /** The nonce this connection must satisfy via Mojang joinServer before it is authenticated. */
    private volatile String pendingServerId;
    private volatile String pendingProfileName;

    // Last reported position, so a late-joining viewer can be sent an immediate snapshot.
    public volatile double x, y, z;
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

    public void beginChallenge(String serverId, String profileName) {
        this.pendingServerId = serverId;
        this.pendingProfileName = profileName;
    }

    public String pendingServerId() {
        return pendingServerId;
    }

    public String pendingProfileName() {
        return pendingProfileName;
    }

    public void authenticate(UUID verifiedUuid, String scope) {
        this.uuid = verifiedUuid;
        this.scope = scope;
        this.authenticated = true;
        this.pendingServerId = null;
        this.pendingProfileName = null;
    }
}
