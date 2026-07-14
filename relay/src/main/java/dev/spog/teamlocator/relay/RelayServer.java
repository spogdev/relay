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
import java.util.LinkedHashSet;
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
                case "trust-update" -> router.setSharing(session.uuid(), parseUuids(obj, "sharingWith"));
                case "block-update" -> router.setBlocked(session.uuid(), parseUuids(obj, "blocked"));
                case "position-update" -> handlePosition(session, obj);
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

        var addr = session.conn().getRemoteSocketAddress();
        var ip = addr != null ? addr.getAddress() : null;

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

    private void handlePosition(Session session, JsonObject obj) {
        session.x = obj.get("x").getAsDouble();
        session.y = obj.get("y").getAsDouble();
        session.z = obj.get("z").getAsDouble();
        session.dimension = obj.has("dimension")
                ? obj.get("dimension").getAsString() : "minecraft:overworld";
        session.hasPosition = true;
        router.broadcastPosition(session);
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

    /** Mirror the mod's address normalization so scopes line up with its per-server trust lists. */
    static String normalizeScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith(":25565")) {
            s = s.substring(0, s.length() - ":25565".length());
        }
        return s;
    }

    @Override
    public void stop(int timeout) throws InterruptedException {
        authPool.shutdownNow();
        super.stop(timeout);
    }
}
