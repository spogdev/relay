package dev.spog.teamlocator.client.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.spog.teamlocator.TeamLocatorConstants;
import dev.spog.teamlocator.client.ClientState;
import dev.spog.teamlocator.client.PingHandler;
import dev.spog.teamlocator.client.TrackedPos;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The mod's connection to the user's relay service: a JDK-builtin {@link WebSocket} (no extra
 * dependency shipped in the mod jar) speaking the relay's JSON protocol.
 *
 * <p>Authentication proves account ownership with Mojang: the relay issues a nonce, we call
 * {@code sessionService.joinServer(profileId, accessToken, nonce)}, and the relay confirms it with
 * {@code hasJoinedServer}. The relay then attributes this connection to the UUID <em>Mojang</em>
 * reports — nothing we claim is trusted, and nobody can impersonate us.
 *
 * <p>Reconnects with capped exponential backoff while the player remains on the same Minecraft
 * server. All sends are serialized through a {@link CompletableFuture} chain because the JDK
 * WebSocket forbids overlapping send operations.
 */
@Environment(EnvType.CLIENT)
public final class RelayClient {
    private static final Gson GSON = new Gson();
    private static final int PROTOCOL_VERSION = 1;
    private static final long MAX_BACKOFF_MS = 30_000L;

    /** Runs the blocking Mojang joinServer call and reconnect timers; never the game thread. */
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "teamlocator-relay");
        t.setDaemon(true);
        return t;
    });

    /** Supplies the current trust set from config (breaks a class cycle). */
    private final Supplier<Set<UUID>> sharingSet;

    private volatile WebSocket socket;
    private volatile boolean authenticated;
    /** Set while the player is on a server and wants the relay; cleared by {@link #disconnect()}. */
    private volatile boolean active;
    private volatile String url = "";
    private volatile String mcServerKey = "";
    private volatile long backoffMs = 1_000L;

    /** Serializes sendText calls; the JDK WebSocket rejects overlapping sends. */
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
    private final Object sendLock = new Object();

    public RelayClient(Supplier<Set<UUID>> sharingSet) {
        this.sharingSet = sharingSet;
    }

    public boolean isReady() {
        return authenticated && socket != null;
    }

    /** Connect (or switch) to the given relay for the given Minecraft-server scope. */
    public void connect(String relayUrl, String serverKey) {
        disconnect();
        if (relayUrl == null || relayUrl.isBlank() || serverKey == null) {
            return;
        }
        this.url = relayUrl.trim();
        this.mcServerKey = serverKey;
        this.active = true;
        this.backoffMs = 1_000L;
        executor.execute(this::open);
    }

    /** Drop the connection and stop reconnecting (player left the server or changed the URL). */
    public void disconnect() {
        active = false;
        authenticated = false;
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            try {
                ws.abort();
            } catch (Exception ignored) {
            }
        }
    }

    private void open() {
        if (!active) {
            return;
        }
        try {
            // Pin HTTP/1.1: with the default client, ALPN can negotiate HTTP/2 against servers
            // that support it (e.g. Caddy), and a WebSocket upgrade cannot ride an h2 connection —
            // the server answers 200 instead of 101 and the handshake fails.
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build()
                    .newWebSocketBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .buildAsync(URI.create(url), new Listener())
                    .whenComplete((ws, err) -> {
                        if (err != null) {
                            logConnectFailure(err);
                            scheduleReconnect();
                        } else {
                            socket = ws;
                            sendJson(hello());
                        }
                    });
        } catch (Exception e) {
            TeamLocatorConstants.LOGGER.warn("Relay connect failed: {}", e.toString());
            scheduleReconnect();
        }
    }

    /** Log connect failures with enough detail to diagnose in the field (status, headers). */
    private static void logConnectFailure(Throwable err) {
        Throwable cause = err.getCause() != null ? err.getCause() : err;
        if (cause instanceof java.net.http.WebSocketHandshakeException whe && whe.getResponse() != null) {
            TeamLocatorConstants.LOGGER.warn("Relay handshake rejected: HTTP {} headers={}",
                    whe.getResponse().statusCode(), whe.getResponse().headers().map());
        } else {
            TeamLocatorConstants.LOGGER.warn("Relay connect failed: {}", cause.toString());
        }
    }

    private void scheduleReconnect() {
        authenticated = false;
        socket = null;
        if (!active) {
            return;
        }
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        executor.schedule(this::open, delay, TimeUnit.MILLISECONDS);
    }

    // ---- outbound ----

    private JsonObject hello() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject o = new JsonObject();
        o.addProperty("type", "hello");
        o.addProperty("protocolVersion", PROTOCOL_VERSION);
        o.addProperty("mcServer", mcServerKey);
        o.addProperty("profileName", mc.getUser().getName());
        return o;
    }

    public void sendTrust(Set<UUID> sharingWith) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "trust-update");
        var arr = new com.google.gson.JsonArray();
        sharingWith.forEach(u -> arr.add(u.toString()));
        o.add("sharingWith", arr);
        sendIfReady(o);
    }

    public void sendPosition(double x, double y, double z, String dimension) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "position-update");
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("dimension", dimension);
        sendIfReady(o);
    }

    public void sendAttackPing() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "ping");
        sendIfReady(o);
    }

    private void sendIfReady(JsonObject o) {
        if (isReady()) {
            sendJson(o);
        }
    }

    private void sendJson(JsonObject o) {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        String text = GSON.toJson(o);
        synchronized (sendLock) {
            sendChain = sendChain
                    .exceptionally(ignored -> null) // a failed send must not poison the chain
                    .thenCompose(ignored -> {
                        WebSocket current = socket;
                        return current != null
                                ? current.sendText(text, true)
                                : CompletableFuture.completedFuture(null);
                    });
        }
    }

    // ---- inbound ----

    private final class Listener implements WebSocket.Listener {
        /** The JDK WebSocket may deliver a text frame in fragments; accumulate until last. */
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String message = partial.toString();
                partial.setLength(0);
                try {
                    handle(GSON.fromJson(message, JsonObject.class));
                } catch (JsonParseException | IllegalStateException | NullPointerException e) {
                    TeamLocatorConstants.LOGGER.debug("Malformed relay frame: {}", e.toString());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            TeamLocatorConstants.LOGGER.info("Relay connection closed ({} {})", statusCode, reason);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            TeamLocatorConstants.LOGGER.warn("Relay connection error: {}", error.toString());
            scheduleReconnect();
        }
    }

    private void handle(JsonObject obj) {
        if (obj == null || !obj.has("type")) {
            return;
        }
        switch (obj.get("type").getAsString()) {
            case "auth-challenge" -> onChallenge(obj.get("serverId").getAsString());
            case "auth-ok" -> onAuthOk();
            case "auth-fail" -> {
                TeamLocatorConstants.LOGGER.warn("Relay rejected auth: {}",
                        obj.has("reason") ? obj.get("reason").getAsString() : "?");
                // Do not hammer Mojang's session server with a doomed handshake loop.
                disconnect();
            }
            case "position-snapshot" -> onSnapshot(obj);
            case "ping-broadcast" -> onPingBroadcast(obj);
            case "ping-ack" -> onPingAck(obj);
            default -> { }
        }
    }

    private void onChallenge(String serverId) {
        // joinServer is a blocking HTTP call to Mojang — keep it off the WebSocket receive thread.
        executor.execute(() -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                mc.services().sessionService().joinServer(
                        mc.getUser().getProfileId(), mc.getUser().getAccessToken(), serverId);
                JsonObject o = new JsonObject();
                o.addProperty("type", "auth-response");
                sendJson(o);
            } catch (Exception e) {
                // joinServer also fails transiently (Mojang outage, timeout); killing the relay
                // permanently would leave the HUD silently dead for the session. Retry with
                // backoff — the 30s cap keeps a genuinely offline account from hammering Mojang.
                TeamLocatorConstants.LOGGER.warn("Mojang joinServer failed, will retry: {}", e.toString());
                WebSocket ws = socket;
                if (ws != null) {
                    try {
                        ws.abort(); // abort() invokes neither onClose nor onError — no double reconnect
                    } catch (Exception ignored) {
                    }
                }
                scheduleReconnect();
            }
        });
    }

    private void onAuthOk() {
        authenticated = true;
        backoffMs = 1_000L;
        TeamLocatorConstants.LOGGER.info("Relay authenticated");
        // The relay lost our routing state with the old socket; push it fresh.
        sendTrust(sharingSet.get());
    }

    private void onSnapshot(JsonObject obj) {
        if (!obj.has("entries") || !obj.get("entries").isJsonArray()) {
            return;
        }
        for (var el : obj.getAsJsonArray("entries")) {
            try {
                JsonObject e = el.getAsJsonObject();
                UUID id = UUID.fromString(e.get("id").getAsString());
                Identifier dim = Identifier.tryParse(e.get("dimension").getAsString());
                ClientState.updatePosition(new TrackedPos(
                        id,
                        e.get("x").getAsDouble(),
                        e.get("y").getAsDouble(),
                        e.get("z").getAsDouble(),
                        dim != null ? dim : Identifier.parse("minecraft:overworld")));
            } catch (RuntimeException ex) {
                TeamLocatorConstants.LOGGER.debug("Bad snapshot entry: {}", ex.toString());
            }
        }
    }

    private void onPingBroadcast(JsonObject obj) {
        try {
            UUID attacker = UUID.fromString(obj.get("attacker").getAsString());
            // An old relay omits mcServer; assume same-server, which was its only behavior.
            String fromServer = obj.has("mcServer")
                    ? obj.get("mcServer").getAsString() : mcServerKey;
            PingHandler.onPing(attacker, fromServer, mcServerKey);
        } catch (RuntimeException e) {
            TeamLocatorConstants.LOGGER.debug("Bad ping broadcast: {}", e.toString());
        }
    }

    /** The relay telling us who received our own ping (an old relay never sends this). */
    private void onPingAck(JsonObject obj) {
        if (!obj.has("receivers") || !obj.get("receivers").isJsonArray()) {
            return;
        }
        List<UUID> receivers = new ArrayList<>();
        for (var el : obj.getAsJsonArray("receivers")) {
            try {
                receivers.add(UUID.fromString(el.getAsString()));
            } catch (RuntimeException ignored) {
                // skip a malformed uuid rather than dropping the whole ack
            }
        }
        PingHandler.onPingAck(receivers);
    }
}
