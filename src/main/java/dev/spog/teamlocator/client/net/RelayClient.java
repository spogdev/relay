package dev.spog.teamlocator.client.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.spog.teamlocator.TeamLocatorConstants;
import dev.spog.teamlocator.client.ClientState;
import dev.spog.teamlocator.client.PingHandler;
import dev.spog.teamlocator.client.RelayToasts;
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
 * <p>Reconnects with capped exponential backoff for as long as the relay is wanted — including
 * after a rejected handshake, which a relay reboot produces for every in-flight client — so a
 * restarted relay is picked up again automatically. All sends are serialized through a
 * {@link CompletableFuture} chain because the JDK WebSocket forbids overlapping send operations.
 */
@Environment(EnvType.CLIENT)
public final class RelayClient {
    private static final Gson GSON = new Gson();
    /** 2 adds armor sharing. The relay rejects a mismatch, so both sides must be updated together. */
    private static final int PROTOCOL_VERSION = 2;
    /**
     * Ceiling for reconnect backoff. 15s so a relay that goes down (reboot, redeploy) is picked up
     * again within ~15s of returning, rather than leaving clients dark for up to half a minute.
     */
    private static final long MAX_BACKOFF_MS = 15_000L;
    /** Floor for the retry delay after a rejected handshake; see {@link #onAuthFail(String)}. */
    private static final long AUTH_FAIL_BACKOFF_MS = 15_000L;

    /** Runs the blocking Mojang joinServer call and reconnect timers; never the game thread. */
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "teamlocator-relay");
        t.setDaemon(true);
        return t;
    });

    /** Supplies the current position-sharing set from config (breaks a class cycle). */
    private final Supplier<Set<UUID>> sharingSet;
    /** Supplies the current alert-trust set from config; gates ping delivery relay-side. */
    private final Supplier<Set<UUID>> alertSet;
    /**
     * Run once every time auth succeeds, including after a reconnect. Lets the client re-push state
     * the relay only learns incrementally (armor), which a new socket would otherwise never see.
     */
    private final Runnable onReauthenticated;

    private volatile WebSocket socket;
    private volatile boolean authenticated;
    /** Set while the player is on a server and wants the relay; cleared by {@link #disconnect()}. */
    private volatile boolean active;
    private volatile String url = "";
    private volatile String mcServerKey = "";
    private volatile long backoffMs = 1_000L;
    /**
     * Generation counter for connection attempts. Every open() claims the next number, and every
     * async callback (handshake completion, listener events, the Mojang auth task) first checks it
     * still holds the latest one; a superseded attempt aborts its socket and goes silent instead of
     * acting. Without this, an attempt whose handshake was still in flight when a reconnect timer
     * fired could complete anyway, leaving TWO live sockets for one account — the relay then kicks
     * whichever authenticated first, the kicked one's listener schedules another connection, and
     * the two sockets kick each other forever (each cycle also burning a Mojang joinServer call,
     * which trips their rate limiter).
     */
    private final java.util.concurrent.atomic.AtomicLong attempt = new java.util.concurrent.atomic.AtomicLong();
    /**
     * True once we have told the user the connection dropped. Gates the connection toasts to real
     * state changes: reconnect attempts run every 15s while a relay is down, and toasting each
     * failure — or each success after a merely momentary blip — would be noise. Set when a
     * previously-authenticated connection is lost, cleared when auth succeeds again.
     */
    private volatile boolean notifiedDisconnect;

    /** Serializes sendText calls; the JDK WebSocket rejects overlapping sends. */
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
    private final Object sendLock = new Object();

    public RelayClient(Supplier<Set<UUID>> sharingSet, Supplier<Set<UUID>> alertSet,
                       Runnable onReauthenticated) {
        this.sharingSet = sharingSet;
        this.alertSet = alertSet;
        this.onReauthenticated = onReauthenticated;
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
        // Invalidate every in-flight attempt: a handshake completing after this must abort itself,
        // not install a socket the teardown could no longer see.
        attempt.incrementAndGet();
        // A deliberate teardown is not an outage: clear the edge so the next connect does not
        // report itself as a recovery from a drop the user never saw.
        notifiedDisconnect = false;
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
        long gen = attempt.incrementAndGet();
        try {
            // Pin HTTP/1.1: with the default client, ALPN can negotiate HTTP/2 against servers
            // that support it (e.g. Caddy), and a WebSocket upgrade cannot ride an h2 connection —
            // the server answers 200 instead of 101 and the handshake fails.
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build()
                    .newWebSocketBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .buildAsync(URI.create(url), new Listener(gen))
                    .whenComplete((ws, err) -> {
                        if (err != null) {
                            logConnectFailure(err);
                            scheduleReconnect(gen);
                        } else if (gen != attempt.get() || !active) {
                            // A newer attempt (or a disconnect) superseded this handshake while it
                            // was in flight; installing it would leave two live sockets.
                            abortQuietly(ws);
                        } else {
                            socket = ws;
                            sendJson(hello());
                        }
                    });
        } catch (Exception e) {
            TeamLocatorConstants.LOGGER.warn("Relay connect failed: {}", e.toString());
            scheduleReconnect(gen);
        }
    }

    private static void abortQuietly(WebSocket ws) {
        try {
            ws.abort();
        } catch (Exception ignored) {
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

    private void scheduleReconnect(long gen) {
        // Only the latest attempt may drive the retry loop. A superseded socket reporting its own
        // death (the relay kicks the older of two connections for the same account) must not spawn
        // yet another connection — that is the two-sockets-kicking-each-other loop.
        if (gen != attempt.get()) {
            return;
        }
        boolean wasAuthenticated = authenticated;
        authenticated = false;
        socket = null;
        if (!active) {
            return;
        }
        // Only the first failure after a working connection is worth a toast: every retry while the
        // relay stays down funnels through here too. A never-authenticated connection (relay down
        // when we joined) stays silent — the user never had the feature to lose.
        if (wasAuthenticated && !notifiedDisconnect) {
            notifiedDisconnect = true;
            RelayToasts.connectionLost();
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

    public void sendTrust(Set<UUID> sharingWith, Set<UUID> alertsWith) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "trust-update");
        o.add("sharingWith", uuidArray(sharingWith));
        // Separate from sharingWith so the share toggle / menu state never kills alert delivery.
        // An old relay simply ignores this field and keeps its single-set behavior.
        o.add("alertsWith", uuidArray(alertsWith));
        sendIfReady(o);
    }

    private static com.google.gson.JsonArray uuidArray(Set<UUID> ids) {
        var arr = new com.google.gson.JsonArray();
        ids.forEach(u -> arr.add(u.toString()));
        return arr;
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

    /**
     * Report our equipped armor. An empty list retracts what the relay holds — that is how opting
     * out of armor sharing takes effect immediately. Only sent when it changes; see
     * {@link dev.spog.teamlocator.client.ArmorReporter}.
     */
    public void sendArmor(List<dev.spog.teamlocator.client.ArmorPiece> pieces) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "armor-update");
        var arr = new com.google.gson.JsonArray();
        for (var piece : pieces) {
            JsonObject p = new JsonObject();
            p.addProperty("slot", piece.slot());
            p.addProperty("item", piece.item());
            p.addProperty("damage", piece.damage());
            p.addProperty("maxDamage", piece.maxDamage());
            arr.add(p);
        }
        o.add("armor", arr);
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
        /** The attempt this socket belongs to; a superseded socket must not act on anything. */
        private final long gen;

        Listener(long gen) {
            this.gen = gen;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (gen != attempt.get()) {
                // Superseded while still receiving (e.g. its auth-ok racing a newer attempt):
                // acting on the frame would corrupt the newer connection's state.
                abortQuietly(webSocket);
                return null;
            }
            partial.append(data);
            if (last) {
                String message = partial.toString();
                partial.setLength(0);
                try {
                    handle(GSON.fromJson(message, JsonObject.class), gen);
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
            scheduleReconnect(gen);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            TeamLocatorConstants.LOGGER.warn("Relay connection error: {}", error.toString());
            scheduleReconnect(gen);
        }
    }

    private void handle(JsonObject obj, long gen) {
        if (obj == null || !obj.has("type")) {
            return;
        }
        switch (obj.get("type").getAsString()) {
            case "auth-challenge" -> onChallenge(obj.get("serverId").getAsString(), gen);
            case "auth-ok" -> onAuthOk();
            case "auth-fail" -> onAuthFail(obj.has("reason") ? obj.get("reason").getAsString() : "?", gen);
            case "position-snapshot" -> onSnapshot(obj);
            case "ping-broadcast" -> onPingBroadcast(obj);
            case "ping-ack" -> onPingAck(obj);
            default -> { }
        }
    }

    private void onChallenge(String serverId, long gen) {
        // joinServer is a blocking HTTP call to Mojang — keep it off the WebSocket receive thread.
        executor.execute(() -> {
            if (gen != attempt.get()) {
                return; // superseded while queued; don't burn a Mojang call for a dead socket
            }
            try {
                Minecraft mc = Minecraft.getInstance();
                mc.services().sessionService().joinServer(
                        mc.getUser().getProfileId(), mc.getUser().getAccessToken(), serverId);
                JsonObject o = new JsonObject();
                o.addProperty("type", "auth-response");
                sendJson(o);
            } catch (Exception e) {
                // joinServer also fails transiently (Mojang outage, rate limit, timeout); killing
                // the relay permanently would leave the HUD silently dead for the session. Retry
                // with backoff — the cap keeps a genuinely offline account from hammering Mojang.
                TeamLocatorConstants.LOGGER.warn("Mojang joinServer failed, will retry: {}", e.toString());
                WebSocket ws = socket;
                if (ws != null) {
                    abortQuietly(ws); // abort() invokes neither onClose nor onError — no double reconnect
                }
                scheduleReconnect(gen);
            }
        });
    }

    /**
     * The relay refused our handshake. This is not necessarily fatal: when the relay reboots it
     * loses the nonce it issued us, so an in-flight handshake — and any reconnect racing the
     * restart — comes back rejected. Treating that as permanent (the old behavior) left every
     * connected client dead until a manual reconnect, even once the relay was back.
     *
     * <p>So retry on the normal backoff, but floor the delay at {@link #AUTH_FAIL_BACKOFF_MS}: a
     * genuinely doomed account (banned, bad session) then re-handshakes only every 15s rather than
     * hammering Mojang's session server, and the socket is aborted first so the relay is not left
     * holding a dead connection.
     */
    private void onAuthFail(String reason, long gen) {
        TeamLocatorConstants.LOGGER.warn("Relay rejected auth ({}), retrying in {}s",
                reason, AUTH_FAIL_BACKOFF_MS / 1000);
        WebSocket ws = socket;
        if (ws != null) {
            abortQuietly(ws); // abort() invokes neither onClose nor onError — no double reconnect
        }
        backoffMs = Math.max(backoffMs, AUTH_FAIL_BACKOFF_MS);
        scheduleReconnect(gen);
    }

    private void onAuthOk() {
        authenticated = true;
        backoffMs = 1_000L;
        TeamLocatorConstants.LOGGER.info("Relay authenticated");
        // Balance the "lost" toast, so the user knows teammates are live again. Silent on a first
        // connect, which announced nothing to begin with.
        if (notifiedDisconnect) {
            notifiedDisconnect = false;
            RelayToasts.connectionRestored();
        }
        // The relay lost our routing state with the old socket; push it fresh. Armor is only sent
        // on change, so without this a reconnect (a relay reboot, say) would leave teammates seeing
        // no armor for us until our next equipment change.
        sendTrust(sharingSet.get(), alertSet.get());
        onReauthenticated.run();
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
                        dim != null ? dim : Identifier.parse("minecraft:overworld"),
                        parseArmor(e)));
            } catch (RuntimeException ex) {
                TeamLocatorConstants.LOGGER.debug("Bad snapshot entry: {}", ex.toString());
            }
        }
    }

    /**
     * A snapshot entry's armor, or an empty list when the teammate shares none (opted out, or the
     * field is simply absent). A malformed piece is skipped rather than dropping the whole entry —
     * losing a teammate's position over one bad armor field would be a poor trade.
     */
    private static List<dev.spog.teamlocator.client.ArmorPiece> parseArmor(JsonObject entry) {
        if (!entry.has("armor") || !entry.get("armor").isJsonArray()) {
            return List.of();
        }
        List<dev.spog.teamlocator.client.ArmorPiece> pieces = new ArrayList<>();
        for (var el : entry.getAsJsonArray("armor")) {
            try {
                JsonObject p = el.getAsJsonObject();
                pieces.add(new dev.spog.teamlocator.client.ArmorPiece(
                        p.get("slot").getAsString(),
                        p.get("item").getAsString(),
                        p.has("damage") ? p.get("damage").getAsInt() : 0,
                        p.has("maxDamage") ? p.get("maxDamage").getAsInt() : 0));
            } catch (RuntimeException ex) {
                TeamLocatorConstants.LOGGER.debug("Bad armor piece: {}", ex.toString());
            }
        }
        return List.copyOf(pieces);
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
