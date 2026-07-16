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

    /** MC-server scope claimed at hello time; applied only once auth succeeds. */
    private final Map<Session, String> pendingScopes = new ConcurrentHashMap<>();

    /** Mojang verification is a blocking HTTP call; keep it off the WebSocket I/O threads. */
    private final ExecutorService authPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "relay-auth");
        t.setDaemon(true);
        return t;
    });

    public RelayServer(InetSocketAddress address) {
        this(address, new MojangVerifier());
    }

    /** Test seam: lets the routing be exercised without live Mojang accounts. */
    RelayServer(InetSocketAddress address, Verifier verifier) {
        super(address);
        this.verifier = verifier;
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
        int version = obj.has("protocolVersion") ? obj.get("protocolVersion").getAsInt() : 0;
        if (version != Messages.PROTOCOL_VERSION) {
            fail(session, "protocol version mismatch");
            return;
        }

        String profileName = obj.get("profileName").getAsString();
        String scope = normalizeScope(obj.get("mcServer").getAsString());
        session.beginChallenge(verifier.newChallenge(), profileName);
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
                String scope = pendingScopes.remove(session);
                session.authenticate(verified, scope != null ? scope : "unknown");
                registry.register(session);
                registry.send(session, new Messages.AuthOk(verified.toString()));
                // Populate the newcomer's HUD immediately with anyone already sharing with them.
                router.sendInitialSnapshot(session);
                LOG.info("{} ({}) authenticated in scope '{}'", verified, profileName, session.scope());
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
        super.stop(timeout);
    }
}
