package dev.spog.spectaterelay;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.spog.teamlocator.relay.auth.Verifier;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Routes spectate sessions: matches a viewer to a target, tells the target to start capturing, and
 * forwards the resulting stream.
 *
 * <p>A separate process from the main relay on purpose. This service carries orders of magnitude
 * more bytes -- terrain rather than coordinates -- so an overload, a leak or a crash here must not
 * touch the position and attack-alert routing that the rest of Relay depends on.
 *
 * <p>Two rules shape the implementation:
 *
 * <p><b>Frames are opaque.</b> Everything after the type byte of a stream frame is forwarded
 * without being read. The service has no Minecraft dependency and cannot parse a game packet, which
 * means it needs no updating when packet formats change, and a malformed stream cannot be
 * interpreted into anything here.
 *
 * <p><b>No client-supplied identity is trusted.</b> As in the main relay, every frame is attributed
 * to the UUID verified during the Mojang handshake. A viewer names the target it wants; it cannot
 * name itself.
 */
public final class SpectateRelayServer extends WebSocketServer {
    private static final Logger LOG = LoggerFactory.getLogger(SpectateRelayServer.class);

    /** Reject a text frame larger than this; the control protocol needs only a few hundred bytes. */
    private static final int MAX_CONTROL_FRAME = 8 * 1024;

    /**
     * Reject a stream frame larger than this. A chunk packet at the capture radius is tens of
     * kilobytes; this leaves generous headroom while refusing a frame that could only be an attempt
     * to exhaust memory.
     */
    private static final int MAX_STREAM_FRAME = 4 * 1024 * 1024;

    private final Gson gson = new Gson();
    private final Verifier verifier;

    /** Mojang verification is a blocking HTTP call; keep it off the socket threads. */
    private final ExecutorService authPool =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "spectate-auth");
                t.setDaemon(true);
                return t;
            });

    private final Map<WebSocket, SpectateSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, SpectateSession> byUuid = new ConcurrentHashMap<>();

    public SpectateRelayServer(InetSocketAddress address, Verifier verifier) {
        super(address);
        this.verifier = verifier;
        setReuseAddr(true);
    }

    @Override
    public void onStart() {
        LOG.info("spectate relay listening on {}", getAddress());
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        sessions.put(conn, new SpectateSession(conn));
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        SpectateSession session = sessions.remove(conn);
        if (session == null) {
            return;
        }
        UUID id = session.uuid();
        if (id != null) {
            byUuid.remove(id, session);
        }
        // Tear down both roles. A target going away must end every session watching it, and a
        // viewer going away must stop the capture it caused -- otherwise the target keeps paying
        // to serialize a stream nobody receives.
        endAsTarget(session);
        endAsViewer(session);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOG.warn("socket error", ex);
    }

    /** Control messages are JSON text, matching the main relay's handshake shape. */
    @Override
    public void onMessage(WebSocket conn, String message) {
        if (message.length() > MAX_CONTROL_FRAME) {
            conn.close();
            return;
        }
        SpectateSession session = sessions.get(conn);
        if (session == null) {
            return;
        }
        try {
            JsonObject obj = gson.fromJson(message, JsonObject.class);
            if (obj == null || !obj.has("type")) {
                return;
            }
            switch (obj.get("type").getAsString()) {
                case "hello" -> handleHello(session, obj);
                case "auth-response" -> handleAuthResponse(session);
                case "trust-update" -> handleTrustUpdate(session, obj);
                default -> { }
            }
        } catch (JsonSyntaxException | UnsupportedOperationException | IllegalStateException e) {
            LOG.debug("malformed control frame", e);
        }
    }

    /** Stream and session frames are binary. */
    @Override
    public void onMessage(WebSocket conn, ByteBuffer frame) {
        if (frame.remaining() > MAX_STREAM_FRAME) {
            conn.close();
            return;
        }
        SpectateSession session = sessions.get(conn);
        if (session == null || !session.isAuthenticated()) {
            return;
        }
        switch (Wire.typeOf(frame)) {
            case Wire.TYPE_SESSION_REQUEST -> handleSessionRequest(session, frame);
            case Wire.TYPE_SESSION_STOP -> endAsViewer(session);
            case Wire.TYPE_HANDSHAKE, Wire.TYPE_LOGIN_PACKET, Wire.TYPE_GAME_PACKET,
                 Wire.TYPE_TARGET_POSITION -> forwardToViewers(session, frame);
            case Wire.TYPE_CHUNK_REQUEST -> forwardToTarget(session, frame);
            case Wire.TYPE_CAPTURE_UNAVAILABLE -> denyViewersOf(session);
            case Wire.TYPE_STREAM_END -> endAsTarget(session);
            default -> { }
        }
    }

    // ---- handshake ----

    private void handleHello(SpectateSession session, JsonObject obj) {
        int version = obj.has("version") ? obj.get("version").getAsInt() : 0;
        if (version != Wire.VERSION) {
            send(session, "{\"type\":\"auth-fail\",\"reason\":\"version\"}");
            session.conn().close();
            return;
        }
        String name = obj.has("profileName") ? obj.get("profileName").getAsString() : null;
        if (name == null || name.isBlank()) {
            session.conn().close();
            return;
        }
        String challenge = verifier.newChallenge();
        session.beginChallenge(challenge, name);
        JsonObject out = new JsonObject();
        out.addProperty("type", "auth-challenge");
        out.addProperty("serverId", challenge);
        send(session, gson.toJson(out));
    }

    private void handleAuthResponse(SpectateSession session) {
        String serverId = session.pendingServerId();
        String name = session.profileName();
        if (serverId == null || name == null) {
            session.conn().close();
            return;
        }
        // Skip Mojang's optional IP cross-check by passing null, exactly as the main relay does
        // and as vanilla does by default. Behind a reverse proxy the remote address is always
        // 127.0.0.1, while Mojang recorded the player's real public IP at joinServer time, so the
        // check fails for every player -- which presents as "unverified" and is indistinguishable
        // from a genuinely bad session.
        authPool.submit(() -> {
            try {
                UUID verified = verifier.verify(name, serverId, null);
                if (verified == null) {
                    send(session, "{\"type\":\"auth-fail\",\"reason\":\"unverified\"}");
                    session.conn().close();
                    return;
                }
                session.authenticated(verified);
                byUuid.put(verified, session);
                send(session, "{\"type\":\"auth-ok\"}");
                LOG.info("authenticated {} ({})", name, verified);
            } catch (Verifier.Unavailable e) {
                send(session, "{\"type\":\"auth-fail\",\"reason\":\"retry\"}");
                session.conn().close();
            }
        });
    }

    private void handleTrustUpdate(SpectateSession session, JsonObject obj) {
        if (!session.isAuthenticated() || !obj.has("trusts")) {
            return;
        }
        Set<UUID> next = new HashSet<>();
        for (var el : obj.getAsJsonArray("trusts")) {
            try {
                next.add(UUID.fromString(el.getAsString()));
            } catch (IllegalArgumentException ignored) {
                // A malformed uuid in the list is skipped rather than rejecting the whole update.
            }
        }
        session.setTrusts(next);

        // Revoking trust must take effect immediately, not at the next session request: a viewer
        // already watching has to be cut off.
        for (UUID viewer : session.viewers()) {
            if (!session.trusts(viewer)) {
                SpectateSession viewing = byUuid.get(viewer);
                if (viewing != null) {
                    viewing.conn().send(Wire.deny(Wire.DENY_REVOKED, "Trust revoked"));
                    stopWatching(viewing, session);
                }
            }
        }
    }

    // ---- sessions ----

    private void handleSessionRequest(SpectateSession viewer, ByteBuffer frame) {
        UUID targetId = Wire.readUuid(frame);
        if (targetId == null) {
            return;
        }
        if (targetId.equals(viewer.uuid())) {
            viewer.conn().send(Wire.deny(Wire.DENY_NOT_TRUSTED, "Cannot spectate yourself"));
            return;
        }
        if (viewer.watching() != null) {
            viewer.conn().send(Wire.deny(Wire.DENY_BUSY, "Already spectating"));
            return;
        }
        SpectateSession target = byUuid.get(targetId);
        if (target == null) {
            // Indistinguishable from the outside: the target may be offline, or may simply not run
            // the addon. Reporting one reason for both avoids telling a viewer who has the addon.
            viewer.conn().send(Wire.deny(Wire.DENY_OFFLINE, "Target unavailable"));
            return;
        }
        if (!target.trusts(viewer.uuid())) {
            viewer.conn().send(Wire.deny(Wire.DENY_NOT_TRUSTED, "Target does not trust you"));
            return;
        }
        target.addViewer(viewer.uuid());
        viewer.setWatching(targetId);
        target.conn().send(Wire.of(Wire.TYPE_CAPTURE_START, viewer.uuid()));
        viewer.conn().send(Wire.of(Wire.TYPE_SESSION_READY, targetId));
        LOG.info("{} began spectating {}", viewer.uuid(), targetId);
    }

    /**
     * Forwards one frame from a viewer to the target it is watching.
     *
     * <p>The reverse of {@link #forwardToViewers}, and the only direction that goes this way. The
     * recipient is not named by the sender: it is {@code viewer.watching()}, which the service set
     * itself when the session was authorised. A viewer therefore cannot address a frame to a
     * client that has not accepted it, which is the same rule the rest of the protocol follows --
     * no client-supplied identity is trusted.
     *
     * <p>The payload is forwarded unread, like every other stream frame.
     */
    private void forwardToTarget(SpectateSession viewer, ByteBuffer frame) {
        UUID targetId = viewer.watching();
        if (targetId == null) {
            LOG.debug("drop frame type {}: {} is not watching anyone",
                    Wire.typeOf(frame), viewer.uuid());
            return;
        }
        SpectateSession target = byUuid.get(targetId);
        // Logged rather than silently dropped, for the same reason the other direction is: a
        // frame lost at this hop appears in neither client's log, since each can only see what it
        // sent or received.
        if (target == null || !target.conn().isOpen()) {
            LOG.warn("drop frame type {}: target {} unavailable", Wire.typeOf(frame), targetId);
            return;
        }
        if (!target.viewers().contains(viewer.uuid())) {
            LOG.warn("drop frame type {}: {} not a viewer of {}",
                    Wire.typeOf(frame), viewer.uuid(), targetId);
            return;
        }
        target.conn().send(frame.duplicate());
    }

    /**
     * Forwards one stream frame from a target to every viewer watching it.
     *
     * <p>The frame is passed on whole and unread. {@code ByteBuffer} is duplicated per recipient
     * because each send consumes its own position.
     */
    private void forwardToViewers(SpectateSession target, ByteBuffer frame) {
        if (!target.hasViewers()) {
            LOG.debug("drop frame type {}: target {} has no viewers",
                    Wire.typeOf(frame), target.uuid());
            return;
        }
        byte type = Wire.typeOf(frame);
        for (UUID viewerId : target.viewers()) {
            SpectateSession viewer = byUuid.get(viewerId);
            // Each condition is logged on failure rather than silently skipped. A frame dropped
            // here is invisible in both clients' logs -- the target reports what it sent and the
            // viewer reports what it received, and neither can see this hop -- so a silent drop
            // here is the hardest kind of loss to locate.
            if (viewer == null) {
                LOG.warn("drop frame type {}: viewer {} has no session", type, viewerId);
                continue;
            }
            if (!viewer.conn().isOpen()) {
                LOG.warn("drop frame type {}: viewer {} socket closed", type, viewerId);
                continue;
            }
            if (!target.uuid().equals(viewer.watching())) {
                LOG.warn("drop frame type {}: viewer {} watching {} not target {}",
                        type, viewerId, viewer.watching(), target.uuid());
                continue;
            }
            viewer.conn().send(frame.duplicate());
            target.countForwarded();
        }
    }

    /**
     * The target has no world to share, so every viewer waiting on it is told rather than left
     * waiting for a handshake that is never coming.
     */
    private void denyViewersOf(SpectateSession target) {
        for (UUID viewerId : target.viewers()) {
            SpectateSession viewer = byUuid.get(viewerId);
            if (viewer != null && viewer.conn().isOpen()) {
                viewer.conn().send(Wire.deny(Wire.DENY_OFFLINE, "Target is not in a world"));
            }
            target.removeViewer(viewerId);
            if (viewer != null) {
                viewer.setWatching(null);
            }
        }
    }

    /** Ends every session in which this client is the target. */
    private void endAsTarget(SpectateSession target) {
        UUID targetId = target.uuid();
        if (targetId == null) {
            return;
        }
        for (UUID viewerId : target.viewers()) {
            SpectateSession viewer = byUuid.get(viewerId);
            if (viewer != null && viewer.conn().isOpen()) {
                viewer.conn().send(Wire.of(Wire.TYPE_STREAM_END));
                viewer.setWatching(null);
            }
        }
        for (UUID viewerId : target.viewers()) {
            target.removeViewer(viewerId);
        }
    }

    /** Ends the session in which this client is the viewer. */
    private void endAsViewer(SpectateSession viewer) {
        UUID targetId = viewer.watching();
        if (targetId == null) {
            return;
        }
        SpectateSession target = byUuid.get(targetId);
        if (target != null) {
            stopWatching(viewer, target);
        } else {
            viewer.setWatching(null);
        }
    }

    /** Detaches one viewer from one target, telling the target to stop if nobody is left. */
    private void stopWatching(SpectateSession viewer, SpectateSession target) {
        viewer.setWatching(null);
        target.removeViewer(viewer.uuid());
        if (target.conn().isOpen()) {
            target.conn().send(Wire.of(Wire.TYPE_CAPTURE_STOP, viewer.uuid()));
        }
        LOG.info("{} stopped spectating {}", viewer.uuid(), target.uuid());
    }

    private void send(SpectateSession session, String json) {
        if (session.conn().isOpen()) {
            session.conn().send(json);
        }
    }
}
