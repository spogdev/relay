package dev.spog.teamlocator.relay;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.spog.teamlocator.relay.auth.MojangVerifier;
import dev.spog.teamlocator.relay.auth.Verifier;
import dev.spog.teamlocator.relay.protocol.Messages;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The relay: a WebSocket service that routes coordinates and attack pings between mutually-trusting
 * TeamLocator clients, independently of any Minecraft server. Clients authenticate with their real
 * Mojang account (see {@link MojangVerifier}), so a UUID cannot be impersonated.
 */
public final class RelayServer extends WebSocketServer {
    private static final Logger LOG = LoggerFactory.getLogger(RelayServer.class);

    /** The four armor slots a client may report; anything else is dropped. */
    private static final Set<String> ARMOR_SLOTS = Set.of("head", "chest", "legs", "feet");
    private static final int MAX_ARMOR_PIECES = 4;
    /** Generous bound for a namespaced item id; keeps a hostile client from sending a huge string. */
    private static final int MAX_ITEM_ID_LENGTH = 256;

    private final Gson gson = new Gson();
    private final SessionRegistry registry = new SessionRegistry();
    private final RelayRouter router = new RelayRouter(registry);
    private final Verifier verifier;
    private final AdminService admins;

    /** MC-server scope claimed at hello time; applied only once auth succeeds. */
    private final Map<Session, String> pendingScopes = new ConcurrentHashMap<>();

    /** Mojang verification is a blocking HTTP call; keep it off the WebSocket I/O threads. */
    private final ExecutorService authPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "relay-auth");
        t.setDaemon(true);
        return t;
    });

    public RelayServer(InetSocketAddress address) {
        this(address, new MojangVerifier(), AdminService.disabled());
    }

    public RelayServer(InetSocketAddress address, AdminService admins) {
        this(address, new MojangVerifier(), admins);
    }

    /** Test seam: lets the routing be exercised without live Mojang accounts. */
    RelayServer(InetSocketAddress address, Verifier verifier) {
        this(address, verifier, AdminService.disabled());
    }

    RelayServer(InetSocketAddress address, Verifier verifier, AdminService admins) {
        super(address);
        this.verifier = verifier;
        this.admins = admins;
        setReuseAddr(true);
    }

    @Override
    public void onStart() {
        LOG.info("TeamLocator relay listening on {}", getAddress());
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        registry.open(conn);
        LOG.debug("connection opened from {}", conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Session s = registry.get(conn);
        if (s != null) {
            pendingScopes.remove(s);
            if (s.authenticated()) {
                router.remove(s.uuid());
                LOG.info("{} disconnected", s.uuid());
            }
        }
        registry.close(conn);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOG.warn("socket error: {}", ex.toString());
        if (conn != null) {
            registry.close(conn);
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        Session session = registry.get(conn);
        if (session == null) {
            conn.close();
            return;
        }
        try {
            JsonObject obj = gson.fromJson(message, JsonObject.class);
            if (obj == null || !obj.has("type")) {
                return;
            }
            String type = obj.get("type").getAsString();

            // Only the handshake is allowed before authentication, so an unverified socket can
            // never inject trust state or read anyone's position.
            if (!session.authenticated()) {
                switch (type) {
                    case "hello" -> handleHello(session, obj);
                    case "auth-response" -> handleAuthResponse(session);
                    default -> LOG.debug("dropping {} from unauthenticated socket", type);
                }
                return;
            }

            switch (type) {
                case "trust-update" -> handleTrustUpdate(session, obj);
                case "block-update" -> router.setBlocked(session.uuid(), parseUuids(obj, "blocked"));
                case "position-update" -> handlePosition(session, obj);
                case "armor-update" -> handleArmor(session, obj);
                case "ping" -> router.handlePing(session);
                case "availability-query" -> router.handleAvailabilityQuery(session);
                case "availability-response" -> handleAvailabilityResponse(session, obj);
                case "map-ping" -> handleMapPing(session, obj);
                case "chat" -> handleChat(session, obj);
                case "share-waypoint" -> handleShareWaypoint(session, obj);
                case "admin-command" -> handleAdminCommand(session, obj);
                default -> LOG.debug("unknown message type {}", type);
            }
        } catch (JsonParseException | IllegalStateException | NullPointerException
                 | UnsupportedOperationException e) {
            LOG.debug("malformed frame: {}", e.toString());
        }
    }

    private void handleHello(Session session, JsonObject obj) {
        if (!obj.has("profileName") || !obj.has("mcServer")) {
            fail(session, "missing fields");
            return;
        }
        int claimed = obj.has("protocolVersion") ? obj.get("protocolVersion").getAsInt() : 0;
        // Accept any client at or above the supported floor. A client older than the floor is
        // genuinely unroutable and is turned away; a client newer than us is served as if it spoke
        // our version — its extra fields are simply ignored downstream — so a not-yet-updated relay
        // never rejects an updated mod. See Messages.MIN_SUPPORTED_VERSION.
        if (claimed < Messages.MIN_SUPPORTED_VERSION) {
            fail(session, "protocol too old (relay supports v" + Messages.MIN_SUPPORTED_VERSION
                    + "+, client is v" + claimed + ")");
            return;
        }
        int negotiated = Math.min(claimed, Messages.PROTOCOL_VERSION);

        String profileName = obj.get("profileName").getAsString();
        String scope = normalizeScope(obj.get("mcServer").getAsString());
        session.beginChallenge(verifier.newChallenge(), profileName, negotiated);
        pendingScopes.put(session, scope);

        registry.send(session, new Messages.AuthChallenge(session.pendingServerId()));
    }

    private void handleAuthResponse(Session session) {
        String serverId = session.pendingServerId();
        String profileName = session.pendingProfileName();
        if (serverId == null || profileName == null) {
            fail(session, "no challenge issued");
            return;
        }

        // Skip Mojang's optional IP cross-check (pass null, like vanilla's default). Behind a
        // reverse proxy (Caddy) the remote address is always 127.0.0.1, while Mojang recorded the
        // player's real public IP at joinServer time — the check would fail for every player.
        final java.net.InetAddress ip = null;

        authPool.submit(() -> {
            try {
                UUID verified = verifier.verify(profileName, serverId, ip);
                if (verified == null) {
                    fail(session, "mojang did not verify this session");
                    return;
                }
                // Enforced only now: the blacklist is keyed by UUID, and the UUID is not known
                // (and must not be believed) until Mojang has vouched for it.
                if (admins.isBanned(verified)) {
                    LOG.info("refused blacklisted player {} ({})", profileName, verified);
                    pendingScopes.remove(session);
                    fail(session, Messages.REASON_BANNED);
                    return;
                }
                String scope = pendingScopes.remove(session);
                session.authenticate(verified, scope != null ? scope : "unknown");
                registry.register(session);
                admins.recordLogin(verified);
                // The admin flag is a UI hint only — it lets the client hide /relay from players who
                // cannot use it. Every command is still authorized relay-side against the verified
                // UUID, so a client that lies about this to itself gains nothing.
                registry.send(session, new Messages.AuthOk(verified.toString(),
                        admins.isAdmin(verified)));
                // Populate the newcomer's HUD immediately with anyone already sharing with them.
                router.sendInitialSnapshot(session);
                LOG.info("{} ({}) authenticated in scope '{}' (protocol v{})",
                        verified, profileName, session.scope(), session.protocolVersion());
            } catch (Verifier.Unavailable e) {
                LOG.warn("Mojang session server unavailable: {}", e.getCause().toString());
                fail(session, "mojang session server unavailable");
            } catch (Exception e) {
                LOG.warn("auth error", e);
                session.conn().close();
            }
        });
    }

    private void fail(Session session, String reason) {
        registry.send(session, new Messages.AuthFail(reason));
        session.conn().close();
    }

    /**
     * A location ping. The colour the client asks for is only a request — {@link RelayRouter}
     * validates it against the sender's own palette and applies any administrator override, so what
     * goes out is never simply what came in.
     */
    private void handleMapPing(Session session, JsonObject obj) {
        double x = obj.get("x").getAsDouble();
        double y = obj.get("y").getAsDouble();
        double z = obj.get("z").getAsDouble();
        String dimension = obj.has("dimension")
                ? obj.get("dimension").getAsString() : "minecraft:overworld";
        String requested = obj.has("color") ? obj.get("color").getAsString() : null;
        router.broadcastMapPing(session, x, y, z, dimension, requested,
                admins.pingColorOverride(session.uuid()));
    }

    /** Longest chat message the relay will relay; longer ones are truncated rather than rejected. */
    private static final int MAX_CHAT_LENGTH = 256;
    /** Longest waypoint name accepted, for the same reason. */
    private static final int MAX_WAYPOINT_NAME = 48;

    /**
     * Fan a chat message out to the sender's mutually trusted peers.
     *
     * <p>The text is sanitised here rather than trusted: it is player-authored input that ends up
     * rendered in other people's chat, so control characters (including the section sign Minecraft
     * reads as a formatting escape) are stripped and the length is capped. Doing this relay-side
     * means a patched client cannot bypass it.
     */
    private void handleChat(Session session, JsonObject obj) {
        String text = obj.has("text") ? obj.get("text").getAsString() : "";
        text = sanitize(text, MAX_CHAT_LENGTH);
        if (text.isEmpty()) {
            return; // nothing to say; don't spend a broadcast on it
        }
        router.broadcastChat(session, text);
    }

    /** Offer a waypoint to everyone the sender shares with. One-way: see the router for why. */
    private void handleShareWaypoint(Session session, JsonObject obj) {
        String name = sanitize(obj.has("name") ? obj.get("name").getAsString() : "",
                MAX_WAYPOINT_NAME);
        if (name.isEmpty()) {
            name = "Waypoint";
        }
        String dimension = obj.has("dimension")
                ? obj.get("dimension").getAsString() : "minecraft:overworld";
        router.shareWaypoint(session,
                name,
                obj.get("x").getAsInt(),
                obj.get("y").getAsInt(),
                obj.get("z").getAsInt(),
                dimension);
    }

    /**
     * Strip anything that would let player text control how it renders, and bound its length.
     *
     * <p>Removes the section sign (Minecraft's colour/format escape) and any C0/C1 control
     * characters, so a message cannot recolour itself, forge a fake prefix, or inject newlines that
     * would let one message masquerade as several.
     */
    private static String sanitize(String raw, int maxLength) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(raw.length(), maxLength));
        raw.codePoints().forEach(cp -> {
            if (out.length() >= maxLength) {
                return;
            }
            if (cp == '§' || Character.isISOControl(cp)) {
                return;
            }
            out.appendCodePoint(cp);
        });
        return out.toString().trim();
    }

    /**
     * Run an admin subcommand for an authenticated session. The name lookup can hit Mojang, so it
     * goes on the auth pool rather than blocking a WebSocket I/O thread; the permission check runs
     * inside {@link AdminService#execute} against this session's verified UUID.
     */
    private void handleAdminCommand(Session session, JsonObject obj) {
        String command = obj.has("command") ? obj.get("command").getAsString() : "";
        List<String> args = new ArrayList<>();
        if (obj.has("args") && obj.get("args").isJsonArray()) {
            for (var el : obj.getAsJsonArray("args")) {
                args.add(el.getAsString());
            }
        }
        String callerName = session.profileName();
        authPool.submit(() -> {
            try {
                AdminService.Result result = admins.execute(
                        session.uuid(), callerName, command, args, connectedLookup());
                registry.send(session, new Messages.AdminResult(result.lines(), result.error()));
                // A ban only takes full effect once the banned player is actually gone; do it here
                // rather than inside AdminService so the service stays free of session plumbing.
                if (!result.error() && "blacklist".equalsIgnoreCase(command) && !args.isEmpty()) {
                    kickBanned();
                }
            } catch (RuntimeException e) {
                LOG.warn("admin command '{}' failed", command, e);
                registry.send(session, new Messages.AdminResult(
                        List.of("The command failed. Check the relay log."), true));
            }
        });
    }

    /** Close every live session whose player is now blacklisted. */
    private void kickBanned() {
        for (Session s : registry.allSessions()) {
            if (admins.isBanned(s.uuid())) {
                LOG.info("disconnecting newly-blacklisted {}", s.uuid());
                registry.send(s, new Messages.AuthFail(Messages.REASON_BANNED));
                s.conn().close();
            }
        }
    }

    /** Exposes live-session facts to {@link AdminService} without handing it the registry. */
    private AdminService.ConnectedLookup connectedLookup() {
        return new AdminService.ConnectedLookup() {
            @Override
            public java.util.Optional<UUID> byName(String name) {
                for (Session s : registry.allSessions()) {
                    if (name.equalsIgnoreCase(s.profileName())) {
                        return java.util.Optional.of(s.uuid());
                    }
                }
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<Integer> versionOf(UUID player) {
                return session(player).map(Session::protocolVersion);
            }

            @Override
            public java.util.Optional<String> scopeOf(UUID player) {
                return session(player).map(Session::scope);
            }

            @Override
            public int connectedCount() {
                int n = 0;
                for (Session ignored : registry.allSessions()) {
                    n++;
                }
                return n;
            }

            @Override
            public int scopeCount() {
                Set<String> scopes = new java.util.HashSet<>();
                for (Session s : registry.allSessions()) {
                    if (s.scope() != null) {
                        scopes.add(s.scope());
                    }
                }
                return scopes.size();
            }

            private java.util.Optional<Session> session(UUID player) {
                for (Session s : registry.allSessions()) {
                    if (player.equals(s.uuid())) {
                        return java.util.Optional.of(s);
                    }
                }
                return java.util.Optional.empty();
            }
        };
    }

    /**
     * Apply both trust sets. {@code alertsWith} gates ping delivery separately from position
     * sharing; an old client omits it, in which case its sharing set doubles as the alert set —
     * exactly the single-set behavior that client was written against.
     */
    private void handleTrustUpdate(Session session, JsonObject obj) {
        Set<UUID> sharing = parseUuids(obj, "sharingWith");
        router.setSharing(session.uuid(), sharing);
        router.setAlerts(session.uuid(),
                obj.has("alertsWith") ? parseUuids(obj, "alertsWith") : sharing);
    }

    private void handlePosition(Session session, JsonObject obj) {
        session.x = obj.get("x").getAsDouble();
        session.y = obj.get("y").getAsDouble();
        session.z = obj.get("z").getAsDouble();
        session.dimension = obj.has("dimension")
                ? obj.get("dimension").getAsString() : "minecraft:overworld";
        session.hasPosition = true;
        router.broadcastPosition(session);
    }

    /**
     * Store the client's reported armor and push it to its viewers. An empty or absent list clears
     * it — that is how a client that turns armor sharing off retracts what it already sent, so the
     * data stops flowing the moment the user opts out rather than lingering until reconnect.
     *
     * <p>Everything here is untrusted client input: the piece count is capped, slots are checked
     * against the four real ones, and item ids are length-bounded, so a hostile client cannot use
     * this to blow up viewers' memory or wedge a frame the relay would then fan out.
     */
    private void handleArmor(Session session, JsonObject obj) {
        List<Messages.ArmorPiece> pieces = null;
        if (obj.has("armor") && obj.get("armor").isJsonArray()) {
            var arr = obj.getAsJsonArray("armor");
            List<Messages.ArmorPiece> parsed = new ArrayList<>();
            for (var el : arr) {
                if (parsed.size() >= MAX_ARMOR_PIECES) {
                    break; // four slots exist; ignore anything beyond them
                }
                Messages.ArmorPiece piece = parseArmorPiece(el);
                if (piece != null) {
                    parsed.add(piece);
                }
            }
            pieces = parsed.isEmpty() ? null : List.copyOf(parsed);
        }
        session.armor = pieces;
        router.broadcastArmor(session);
    }

    /** One armor piece, or null if the entry is malformed or names a slot we don't render. */
    private static Messages.ArmorPiece parseArmorPiece(com.google.gson.JsonElement el) {
        if (!el.isJsonObject()) {
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        if (!o.has("slot") || !o.has("item")) {
            return null;
        }
        String slot = o.get("slot").getAsString();
        if (!ARMOR_SLOTS.contains(slot)) {
            return null;
        }
        String item = o.get("item").getAsString();
        if (item.isEmpty() || item.length() > MAX_ITEM_ID_LENGTH) {
            return null;
        }
        int damage = o.has("damage") ? o.get("damage").getAsInt() : 0;
        int maxDamage = o.has("maxDamage") ? o.get("maxDamage").getAsInt() : 0;
        return new Messages.ArmorPiece(slot, item, Math.max(0, damage), Math.max(0, maxDamage));
    }

    /** A probed client's answer for {@code /available}; a frame without a probeId is dropped. */
    private void handleAvailabilityResponse(Session session, JsonObject obj) {
        if (!obj.has("probeId")) {
            return;
        }
        boolean available = obj.has("available") && obj.get("available").getAsBoolean();
        router.handleAvailabilityResponse(session, obj.get("probeId").getAsString(), available);
    }

    private Set<UUID> parseUuids(JsonObject obj, String field) {
        Set<UUID> out = new LinkedHashSet<>();
        if (!obj.has(field) || !obj.get(field).isJsonArray()) {
            return out;
        }
        for (var el : obj.getAsJsonArray(field)) {
            try {
                out.add(UUID.fromString(el.getAsString()));
            } catch (IllegalArgumentException ignored) {
                // Skip a malformed UUID rather than discarding the whole update.
            }
        }
        return out;
    }

    /**
     * Mirror the mod's address normalization so scopes line up with its per-server trust lists.
     *
     * <p>Case and whitespace only. The client now sends the resolved {@code ip:port} of its live
     * connection, which is already canonical, so there is nothing here to reconcile — and stripping
     * {@code :25565} the way this used to would actively break it: a server really running on
     * {@code 1.2.3.4:25565} would keep its port client-side and lose it here, putting the two
     * halves of one scope in different buckets.
     *
     * <p>Older clients still send a typed address, which lands in its own scope. That is a
     * cosmetic split during a version transition — those clients cannot see the new ones anyway,
     * since the scope key is what changed — and it resolves as soon as they update.
     */
    static String normalizeScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public void stop(int timeout) throws InterruptedException {
        authPool.shutdownNow();
        router.shutdown();
        super.stop(timeout);
    }
}
