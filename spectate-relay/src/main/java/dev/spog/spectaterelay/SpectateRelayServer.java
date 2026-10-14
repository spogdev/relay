package dev.spog.spectaterelay;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import dev.spog.spectaterelay.admin.SpectateAdminService;
import dev.spog.teamlocator.relay.auth.Verifier;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /**
     * Access control and the {@code /spegtate} commands.
     *
     * <p>Null when the service was constructed without a data directory, which is what the
     * routing tests do: they exercise forwarding, not moderation, and should not need files on
     * disk. Every use is guarded, and a null service means "no gate" -- the behaviour before
     * this existed.
     */
    private final SpectateAdminService admin;

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
        this(address, verifier, null);
    }

    /**
     * @param admin access control and admin commands, or null to run with no whitelist at all
     *     (the routing tests, which have no data directory)
     */
    public SpectateRelayServer(InetSocketAddress address, Verifier verifier,
                               SpectateAdminService admin) {
        super(address);
        this.verifier = verifier;
        this.admin = admin;
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
        if (id != null) {
            broadcastRoster();
        }
    }

    /**
     * Tells every authenticated client who is currently available to watch.
     *
     * <p>The client used to build this list from its own trust config. With the trust list gone
     * there is nothing on the client that knows who exists, and the service already does, so it
     * says. Only sessions that consent are listed: a client that has spectating turned off is
     * absent rather than listed-and-refused, which keeps the menu honest about what will work.
     *
     * <p>Each recipient is left out of its own roster, because spectating yourself is refused
     * anyway and an unclickable row explaining that is worse than no row.
     */
    private void broadcastRoster() {
        List<SpectateSession> available = new ArrayList<>();
        for (SpectateSession s : byUuid.values()) {
            if (s.isAuthenticated() && s.allowsViewers() && s.uuid() != null) {
                available.add(s);
            }
        }
        int sent = 0;
        for (SpectateSession recipient : byUuid.values()) {
            if (!recipient.isAuthenticated()) {
                continue;
            }
            JsonArray peers = new JsonArray();
            for (SpectateSession s : available) {
                if (s.uuid().equals(recipient.uuid())) {
                    continue;
                }
                JsonObject peer = new JsonObject();
                peer.addProperty("uuid", s.uuid().toString());
                peer.addProperty("name", s.profileName() == null ? "" : s.profileName());
                peer.addProperty("version", s.gameVersion() == null ? "" : s.gameVersion());
                peers.add(peer);
            }
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "roster");
            msg.add("peers", peers);
            send(recipient, msg.toString());
            sent++;
        }
        // Logged because a client showing an empty list cannot say whether the roster arrived
        // empty or never arrived, and neither can this end without saying what it sent.
        LOG.info("roster: {} available, sent to {} client(s)", available.size(), sent);
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
                case "admin-command" -> handleAdminCommand(session, obj);
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
                 Wire.TYPE_TARGET_POSITION, Wire.TYPE_PEER_POSITIONS ->
                    forwardToViewers(session, frame);
            case Wire.TYPE_CHUNK_REQUEST, Wire.TYPE_RESWEEP_REQUEST ->
                    forwardToTarget(session, frame);
            case Wire.TYPE_CAPTURE_UNAVAILABLE -> denyViewersOf(session);
            case Wire.TYPE_STREAM_END -> endAsTarget(session);
            // Logged rather than dropped in silence. A client one build ahead of this service
            // sends frame types it does not know, and the only symptom is a feature that does
            // nothing, with nothing anywhere saying why -- which is exactly how the peer
            // position frames were lost.
            default -> LOG.warn("dropping unknown frame type 0x{} from {}",
                    Integer.toHexString(Wire.typeOf(frame) & 0xFF), session.uuid());
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
        session.setGameVersion(obj.has("gameVersion")
                ? obj.get("gameVersion").getAsString() : null);
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
                // Recorded before the access check, so the all-time figure counts everyone
                // who has the mod rather than everyone who was let in.
                if (admin != null) {
                    admin.recordSeen(verified);
                }
                if (admin != null && !admin.mayConnect(verified)) {
                    // A distinct reason, because the client turns this one into the
                    // "you have not been granted access" screen rather than retrying. Every
                    // other auth-fail is a transient or technical problem worth retrying.
                    send(session, "{\"type\":\"auth-fail\",\"reason\":\"notwhitelisted\"}");
                    LOG.info("refused {} ({}): not whitelisted", name, verified);
                    session.conn().close();
                    return;
                }
                session.authenticated(verified);
                byUuid.put(verified, session);
                // The client is told whether it is an admin so it can offer /spegtate in
                // autocomplete. Cosmetic only -- every command is re-authorized here against
                // the verified UUID, so a client that lies to itself gains nothing.
                JsonObject ok = new JsonObject();
                ok.addProperty("type", "auth-ok");
                ok.addProperty("admin", admin != null && admin.isAdmin(verified));
                send(session, gson.toJson(ok));
                LOG.info("authenticated {} ({})", name, verified);
                // The new client needs the current roster, and everyone else needs to learn
                // about it -- the same message answers both, so it goes to all of them.
                broadcastRoster();
            } catch (Verifier.Unavailable e) {
                send(session, "{\"type\":\"auth-fail\",\"reason\":\"retry\"}");
                session.conn().close();
            }
        });
    }

    private void handleTrustUpdate(SpectateSession session, JsonObject obj) {
        if (!session.isAuthenticated() || !obj.has("allowsViewers")) {
            return;
        }
        boolean was = session.allowsViewers();
        session.setAllowsViewers(obj.get("allowsViewers").getAsBoolean());

        // Clients re-send consent on a timer, not only when it changes, so most updates say
        // what the service already knows. Only a change is worth telling anyone about: the
        // unguarded path here rebroadcast the whole roster to every client every few seconds
        // for as long as one client had spectating turned off.
        if (was == session.allowsViewers()) {
            return;
        }
        LOG.info("{} consent -> {}", session.uuid(), session.allowsViewers());
        broadcastRoster();
        if (session.allowsViewers()) {
            return;
        }

        // Withdrawing consent must take effect immediately, not at the next session request:
        // anyone already watching has to be cut off.
        for (UUID viewer : session.viewers()) {
            SpectateSession viewing = byUuid.get(viewer);
            if (viewing != null) {
                viewing.conn().send(Wire.deny(Wire.DENY_REVOKED, "Spectating disabled"));
                stopWatching(viewing, session);
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
        if (!target.allowsViewers()) {
            viewer.conn().send(Wire.deny(Wire.DENY_NOT_TRUSTED, "Target is not accepting viewers"));
            return;
        }
        // Checked after consent, so a viewer the target is not accepting learns nothing about
        // what the target is running.
        String viewerVersion = viewer.gameVersion();
        String targetVersion = target.gameVersion();
        if (viewerVersion == null || targetVersion == null
                || !viewerVersion.equals(targetVersion)) {
            LOG.info("refused {} -> {}: versions {} and {}",
                    viewer.uuid(), targetId, viewerVersion, targetVersion);
            viewer.conn().send(Wire.deny(Wire.DENY_VERSION,
                    targetVersion == null ? "Target is on a different Minecraft version"
                            : "Target is on Minecraft " + targetVersion));
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

    /**
     * Run a {@code /spegtate} subcommand for an authenticated admin and reply with its lines.
     *
     * <p>Authorization is not checked here: {@link SpectateAdminService#execute} re-checks the
     * caller's verified UUID itself, so there is exactly one place that decides it.
     *
     * <p>Run off the socket thread because a whitelist change resolves a name through Mojang,
     * which blocks. The reply carries the request's {@code id} so a client with two commands in
     * flight can tell the answers apart.
     */
    private void handleAdminCommand(SpectateSession session, JsonObject obj) {
        if (!session.isAuthenticated() || admin == null) {
            return;
        }
        String command = obj.has("command") ? obj.get("command").getAsString() : "";
        List<String> args = new ArrayList<>();
        if (obj.has("args") && obj.get("args").isJsonArray()) {
            for (var el : obj.getAsJsonArray("args")) {
                args.add(el.getAsString());
            }
        }
        String id = obj.has("id") ? obj.get("id").getAsString() : null;
        authPool.submit(() -> {
            SpectateAdminService.Result result = admin.execute(
                    session.uuid(), session.profileName(), command, args, liveState());
            JsonObject out = new JsonObject();
            out.addProperty("type", "admin-result");
            if (id != null) {
                out.addProperty("id", id);
            }
            out.addProperty("error", result.error());
            JsonArray lines = new JsonArray();
            for (String line : result.lines()) {
                lines.add(line);
            }
            out.add("lines", lines);
            send(session, gson.toJson(out));
        });
    }

    /** What the admin commands need to know about the live service. */
    private SpectateAdminService.LiveState liveState() {
        return new SpectateAdminService.LiveState() {
            @Override
            public java.util.Optional<UUID> byName(String name) {
                for (SpectateSession s : byUuid.values()) {
                    if (s.isAuthenticated() && name.equalsIgnoreCase(s.profileName())) {
                        return java.util.Optional.ofNullable(s.uuid());
                    }
                }
                return java.util.Optional.empty();
            }

            @Override
            public int onlineCount() {
                int n = 0;
                for (SpectateSession s : byUuid.values()) {
                    if (s.isAuthenticated()) {
                        n++;
                    }
                }
                return n;
            }

            /**
             * Viewers currently watching someone.
             *
             * <p>Counted as the number of viewers across every target, not the number of
             * targets: two people watching one player is two spectators, and that is what the
             * figure is asked for.
             */
            @Override
            public int spectatingCount() {
                int n = 0;
                for (SpectateSession s : byUuid.values()) {
                    if (s.isAuthenticated()) {
                        n += s.viewers().size();
                    }
                }
                return n;
            }

            @Override
            public List<String> onlineNames() {
                List<String> out = new ArrayList<>();
                for (SpectateSession s : byUuid.values()) {
                    if (s.isAuthenticated() && s.profileName() != null) {
                        out.add(s.profileName());
                    }
                }
                return out;
            }
        };
    }

}
