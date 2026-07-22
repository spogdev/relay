package dev.spog.teamlocator.client.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.spog.teamlocator.TeamLocatorConstants;
import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.config.TeamConfig;
import dev.spog.teamlocator.client.ClientState;
import dev.spog.teamlocator.client.PingHandler;
import dev.spog.teamlocator.client.PingPalette;
import dev.spog.teamlocator.client.PingState;
import dev.spog.teamlocator.client.RelayToasts;
import dev.spog.teamlocator.client.TrackedPos;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import dev.spog.teamlocator.client.RelayChatMessages;
import dev.spog.teamlocator.client.RelaySounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.util.Identifier;

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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
    /**
     * 3 adds availability probes ({@code /available}). The relay accepts older clients from v1 up
     * (and treats newer ones as its own version), so this no longer has to move in lockstep with
     * the relay — but a relay older than the cross-version change still hard-rejects any mismatch,
     * so the relay must be redeployed at least once before shipping this client version.
     */
    private static final int PROTOCOL_VERSION = 3;
    /** How long {@link #queryAvailability} waits for the relay's aggregate before giving up. */
    private static final long AVAILABILITY_TIMEOUT_MS = 4_000L;
    /**
     * Ceiling for reconnect backoff. 15s so a relay that goes down (reboot, redeploy) is picked up
     * again within ~15s of returning, rather than leaving clients dark for up to half a minute.
     */
    private static final long MAX_BACKOFF_MS = 15_000L;
    /** Floor for the retry delay after a rejected handshake; see {@link #onAuthFail(String)}. */
    private static final long AUTH_FAIL_BACKOFF_MS = 15_000L;
    /**
     * Floor for the retry delay after Mojang's own client-side rate limiter refuses a joinServer.
     *
     * <p>authlib wraps joinServer in a RateLimiter that allows roughly one call every 15s and throws
     * ("RateLimiter disallowed request") rather than queueing. Our backoff starts at 1s, so a burst
     * of reconnects — relay restart, redeploy, flaky link — spent its first few attempts inside that
     * window and got refused, and because a refusal scheduled another reconnect it fed itself.
     *
     * <p>Worse, vanilla calls joinServer too when joining a real server. A mod stuck in that loop can
     * eat the token the player's own server join needs, turning log noise into a failed join. So a
     * rate-limit refusal backs off past the limiter's window instead of retrying inside it.
     */
    private static final long RATE_LIMIT_BACKOFF_MS = 20_000L;
    /**
     * The auth-fail reason a relay sends to a blacklisted player. Mirrors
     * {@code Messages.REASON_BANNED}; matched as a code so the relay's wording can change freely.
     */
    private static final String BANNED_REASON = "banned";

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
     * When Mojang's rate limiter last refused a joinServer, so a successful auth immediately after
     * doesn't reset the backoff straight back into the limiter's window. 0 means "never".
     */
    private volatile long lastRateLimitMs = 0L;
    /**
     * When each player's last accepted map ping arrived, for the local per-player ping cooldown.
     * Bounded by the number of players you have ever seen ping in a session, so it needs no eviction.
     */
    private final java.util.Map<UUID, Long> lastMapPingAt = new java.util.concurrent.ConcurrentHashMap<>();
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
    /** Whether the "you are banned" toast has been shown for the current run of ban rejections. */
    private volatile boolean notifiedBanned;
    /**
     * Whether the relay says this account administers it, from the last successful auth. Drives
     * only whether {@code /relay} is offered in chat; the relay authorizes every command itself.
     * Cleared on disconnect so the command disappears rather than lingering against a relay we are
     * no longer talking to (and which may not even be the same relay next time).
     */
    private volatile boolean relayAdmin;

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

    /**
     * Whether the relay reported this account as one of its administrators. Used only to decide if
     * the {@code /relay} command is offered; it is never a permission check in itself.
     */
    public boolean isRelayAdmin() {
        return relayAdmin && isReady();
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
        relayAdmin = false;
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
        MinecraftClient mc = MinecraftClient.getInstance();
        JsonObject o = new JsonObject();
        o.addProperty("type", "hello");
        o.addProperty("protocolVersion", PROTOCOL_VERSION);
        o.addProperty("mcServer", mcServerKey);
        o.addProperty("profileName", mc.getSession().getUsername());
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

    public void sendPosition(double x, double y, double z, String dimension, float health) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "position-update");
        o.addProperty("health", health);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("dimension", dimension);
        // Our ping colour, so teammates can tint our HUD row to match our pings. Sent every update
        // rather than once at auth so changing it takes effect on their HUDs immediately; the relay
        // re-validates it against our own palette either way.
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getSession().getUuidOrNull() != null) {
            o.addProperty("pingColor", PingPalette.forPlayer(
                    mc.getSession().getUuidOrNull(), TeamLocatorClient.CONFIG.pingColorIndex));
        }
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

    /**
     * Place a location ping. {@code color} is only a request: the relay validates it against this
     * account's own palette and applies any administrator override, so what teammates actually see
     * is decided there, not here.
     */
    public void sendMapPing(double x, double y, double z, String dimension, String color) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "map-ping");
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("dimension", dimension);
        o.addProperty("color", color);
        sendIfReady(o);
    }

    /** Withdraw our own map ping, so it clears for teammates too rather than only locally. */
    public void sendRemoveMapPing() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "remove-map-ping");
        sendIfReady(o);
    }

    /** Send a relay chat message. The prefix is a client-side trigger and is never transmitted. */
    public void sendChat(String text) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "chat");
        o.addProperty("text", text);
        sendIfReady(o);
    }

    /** Offer a waypoint to everyone we share with. One-way: they need not trust us back. */
    public void shareWaypoint(String name, int x, int y, int z, String dimension) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "share-waypoint");
        o.addProperty("name", name);
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

    /** The pending {@code /available} callback; one in flight at a time, newest wins. */
    private volatile Consumer<List<UUID>> availabilityCallback;
    private volatile ScheduledFuture<?> availabilityTimeout;

    /**
     * Ask the relay who would actually see our alert right now. {@code onResult} receives the
     * verified receivers (possibly empty), or {@code null} if the relay never answered — an old
     * relay ignores the query outright, so a timeout is the only way to notice. The callback runs
     * on the relay executor thread; hop to the game thread before touching UI.
     */
    public void queryAvailability(Consumer<List<UUID>> onResult) {
        ScheduledFuture<?> priorTimeout = availabilityTimeout;
        if (priorTimeout != null) {
            priorTimeout.cancel(false);
        }
        availabilityCallback = onResult;
        availabilityTimeout = executor.schedule(() -> {
            Consumer<List<UUID>> cb = availabilityCallback;
            availabilityCallback = null;
            if (cb != null) {
                cb.accept(null);
            }
        }, AVAILABILITY_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        JsonObject o = new JsonObject();
        o.addProperty("type", "availability-query");
        sendIfReady(o);
    }

    /** The pending {@code /relay} callback; one in flight at a time, newest wins. */
    private volatile java.util.function.BiConsumer<List<String>, Boolean> adminCallback;
    private volatile ScheduledFuture<?> adminTimeout;

    /**
     * Send an admin subcommand. {@code onResult} receives the relay's reply lines and whether it
     * was an error, or {@code null} lines if the relay never answered — an older relay ignores the
     * frame entirely, so a timeout is the only signal. Runs on the relay executor thread; hop to
     * the game thread before touching UI.
     */
    public void sendAdminCommand(String command, List<String> args,
                                 java.util.function.BiConsumer<List<String>, Boolean> onResult) {
        ScheduledFuture<?> prior = adminTimeout;
        if (prior != null) {
            prior.cancel(false);
        }
        adminCallback = onResult;
        adminTimeout = executor.schedule(() -> {
            var cb = adminCallback;
            adminCallback = null;
            if (cb != null) {
                cb.accept(null, true);
            }
        }, AVAILABILITY_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        JsonObject o = new JsonObject();
        o.addProperty("type", "admin-command");
        o.addProperty("command", command);
        var arr = new com.google.gson.JsonArray();
        args.forEach(arr::add);
        o.add("args", arr);
        sendIfReady(o);
    }

    /**
     * A teammate's location ping. The colour is whatever the relay settled on — our own choice is
     * never applied to someone else's marker, and an administrator override arrives already applied.
     */
    private void onMapPing(JsonObject obj) {
        try {
            UUID owner = UUID.fromString(obj.get("player").getAsString());
            Identifier dim = Identifier.tryParse(obj.get("dimension").getAsString());
            String color = obj.has("color") ? obj.get("color").getAsString() : null;
            if (!mapPingAllowed(owner)) {
                return;
            }
            PingState.put(new PingState.Ping(
                    owner,
                    obj.get("x").getAsDouble(),
                    obj.get("y").getAsDouble(),
                    obj.get("z").getAsDouble(),
                    dim,
                    argbOf(color),
                    System.currentTimeMillis()));
            playMapPingSound(owner);
        } catch (RuntimeException e) {
            TeamLocatorConstants.LOGGER.debug("Bad map ping: {}", e.toString());
        }
    }

    /**
     * Shortest gap between two chimes from the same player. Purely an anti-spam floor: a teammate
     * placing pings faster than this is doing it deliberately, and one sound covers the burst.
     */
    private static final long PING_SOUND_COOLDOWN_MILLIS = 1500L;

    /** When each player last made us chime, so a burst of pings does not machine-gun the sound. */
    private final java.util.Map<UUID, Long> lastPingSoundAt =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Chime for an arriving ping, unless we chimed for this player a moment ago.
     *
     * <p>Fires for our own pings too. The relay echoes every ping back to its placer, so this rides
     * that echo — which means the placer hears the same confirmation everyone else does, and a ping
     * that silently failed to reach the relay is distinguishable from one that landed.
     *
     * <p>An earlier version only chimed when the player had no ping on screen at all. That was
     * badly wrong in practice: pings are keyed per player and last for minutes, so every ping after
     * a teammate's first was silent until their marker expired. The guard is now a short per-player
     * cooldown, which stops a burst from machine-gunning the sound without ever swallowing a
     * genuine new callout.
     */
    private void playMapPingSound(UUID owner) {
        if (!TeamLocatorClient.CONFIG.mapPingSound) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        long now = System.currentTimeMillis();
        Long last = lastPingSoundAt.get(owner);
        if (last != null && now - last < PING_SOUND_COOLDOWN_MILLIS) {
            return;
        }
        lastPingSoundAt.put(owner, now);
        mc.execute(() -> mc.getSoundManager().play(
                PositionedSoundInstance.ui(RelaySounds.PING, 1.0f), 0));
    }

    /**
     * Whether a map ping from {@code owner} is accepted, given the local per-player ping cooldown.
     *
     * <p>Purely a local viewing preference: a rejected ping is simply not drawn here, and nothing is
     * sent back, so it never affects what anyone else sees. Per-sender rather than global so one
     * teammate spamming pings cannot crowd out everyone else's.
     *
     * <p>Your own pings bypass the limit — you placed them deliberately, and having your own marker
     * silently fail to appear would read as the keybind being broken.
     */
    private boolean mapPingAllowed(UUID owner) {
        int cooldown = TeamLocatorClient.CONFIG.mapPingCooldownSeconds;
        if (cooldown <= 0) {
            return true;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getSession().getUuidOrNull() != null && owner.equals(mc.getSession().getUuidOrNull())) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = lastMapPingAt.get(owner);
        if (last != null && now - last < cooldown * 1000L) {
            return false;
        }
        lastMapPingAt.put(owner, now);
        return true;
    }

    /** A teammate withdrew their ping (or the relay echoed our own removal back). */
    private void onMapPingRemoved(JsonObject obj) {
        try {
            PingState.remove(UUID.fromString(obj.get("player").getAsString()));
        } catch (RuntimeException e) {
            TeamLocatorConstants.LOGGER.debug("Bad ping removal: {}", e.toString());
        }
    }

    /**
     * A relay chat message. Rendered on the game thread, since it touches the chat GUI.
     *
     * <p>Honours {@code chatEnabled} as a receive gate too, so turning the feature off silences
     * incoming messages rather than only stopping outgoing ones.
     */
    private void onChat(JsonObject obj) {
        if (!TeamLocatorClient.CONFIG.chatEnabled) {
            return;
        }
        try {
            UUID sender = UUID.fromString(obj.get("player").getAsString());
            String text = obj.get("text").getAsString();
            List<UUID> recipients = new ArrayList<>();
            if (obj.has("recipients")) {
                for (var e : obj.getAsJsonArray("recipients")) {
                    try {
                        recipients.add(UUID.fromString(e.getAsString()));
                    } catch (IllegalArgumentException ignored) {
                        // A malformed entry costs us one name in the tooltip, not the message.
                    }
                }
            }
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> RelayChatMessages.show(mc, sender, text, recipients));
        } catch (RuntimeException e) {
            TeamLocatorConstants.LOGGER.debug("Bad chat frame: {}", e.toString());
        }
    }

    /** A waypoint another player is offering. Presented as an offer; never auto-added. */
    private void onWaypoint(JsonObject obj) {
        try {
            UUID sender = UUID.fromString(obj.get("player").getAsString());
            String name = obj.get("name").getAsString();
            int x = obj.get("x").getAsInt();
            int y = obj.get("y").getAsInt();
            int z = obj.get("z").getAsInt();
            String dimension = obj.has("dimension")
                    ? obj.get("dimension").getAsString() : "minecraft:overworld";
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> RelayChatMessages.showWaypoint(mc, sender, name, x, y, z, dimension));
        } catch (RuntimeException e) {
            TeamLocatorConstants.LOGGER.debug("Bad waypoint frame: {}", e.toString());
        }
    }

    /** {@code #rrggbb} to opaque ARGB, falling back to white if the relay ever sends junk. */
    private static int argbOf(String hex) {
        if (hex != null && hex.length() == 7 && hex.charAt(0) == '#') {
            try {
                return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return 0xFFFFFFFF;
    }

    private void onAdminResult(JsonObject obj) {
        ScheduledFuture<?> pending = adminTimeout;
        if (pending != null) {
            pending.cancel(false);
        }
        var cb = adminCallback;
        adminCallback = null;
        if (cb == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        if (obj.has("lines") && obj.get("lines").isJsonArray()) {
            for (var el : obj.getAsJsonArray("lines")) {
                lines.add(el.getAsString());
            }
        }
        boolean error = obj.has("error") && obj.get("error").getAsBoolean();
        cb.accept(lines, error);
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
            case "auth-ok" -> onAuthOk(obj.has("admin") && obj.get("admin").getAsBoolean());
            case "auth-fail" -> onAuthFail(obj.has("reason") ? obj.get("reason").getAsString() : "?", gen);
            case "position-snapshot" -> onSnapshot(obj);
            case "ping-broadcast" -> onPingBroadcast(obj);
            case "ping-ack" -> onPingAck(obj);
            case "availability-probe" -> onAvailabilityProbe(obj, gen);
            case "map-ping-broadcast" -> onMapPing(obj);
            case "map-ping-removed" -> onMapPingRemoved(obj);
            case "chat-broadcast" -> onChat(obj);
            case "waypoint-broadcast" -> onWaypoint(obj);
            case "admin-result" -> onAdminResult(obj);
            case "availability-result" -> onAvailabilityResult(obj);
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
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.getApiServices().sessionService().joinServer(
                        mc.getSession().getUuidOrNull(), mc.getSession().getAccessToken(), serverId);
                JsonObject o = new JsonObject();
                o.addProperty("type", "auth-response");
                sendJson(o);
            } catch (Exception e) {
                // joinServer also fails transiently (Mojang outage, rate limit, timeout); killing
                // the relay permanently would leave the HUD silently dead for the session. Retry
                // with backoff — the cap keeps a genuinely offline account from hammering Mojang.
                if (isRateLimited(e)) {
                    // Retrying inside authlib's window just gets refused again and re-arms the same
                    // failure, so step outside it. Logged at debug: this is self-correcting and the
                    // warn-level spam was itself part of what users were reporting.
                    TeamLocatorConstants.LOGGER.debug(
                            "Mojang rate-limited joinServer, backing off {}s", RATE_LIMIT_BACKOFF_MS / 1000);
                    lastRateLimitMs = System.currentTimeMillis();
                    backoffMs = Math.max(backoffMs, RATE_LIMIT_BACKOFF_MS);
                } else {
                    TeamLocatorConstants.LOGGER.warn("Mojang joinServer failed, will retry: {}", e.toString());
                }
                WebSocket ws = socket;
                if (ws != null) {
                    abortQuietly(ws); // abort() invokes neither onClose nor onError — no double reconnect
                }
                scheduleReconnect(gen);
            }
        });
    }

    /**
     * Whether a joinServer failure is Mojang's client-side rate limiter rather than a real fault.
     *
     * <p>Matched on the message across the cause chain rather than on an exception type: authlib
     * signals this through a general-purpose exception, so the type alone doesn't identify it, and
     * the wrapper class has changed between versions while the wording has not. A miss here is
     * harmless — it just falls back to the ordinary retry path.
     */
    /** Whether a rate-limit refusal happened recently enough that another join would likely refuse too. */
    private boolean rateLimitedRecently() {
        long at = lastRateLimitMs;
        return at != 0L && System.currentTimeMillis() - at < RATE_LIMIT_BACKOFF_MS;
    }

    private static boolean isRateLimited(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.toLowerCase(java.util.Locale.ROOT).contains("ratelimiter")) {
                return true;
            }
            if (t.getCause() == t) {
                break; // self-referential cause chain; don't spin
            }
        }
        return false;
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
        // A ban is a standing state, not a transient failure: say so plainly, and only once, rather
        // than letting the generic "disconnected, reconnecting..." toast repeat on every retry and
        // imply the relay is merely down. Older relays never send this code, so nothing changes for
        // them. We still retry on the same floor — a lifted ban then reconnects on its own.
        if (BANNED_REASON.equals(reason)) {
            if (!notifiedBanned) {
                notifiedBanned = true;
                RelayToasts.banned();
            }
        } else {
            notifiedBanned = false;
        }
        WebSocket ws = socket;
        if (ws != null) {
            abortQuietly(ws); // abort() invokes neither onClose nor onError — no double reconnect
        }
        backoffMs = Math.max(backoffMs, AUTH_FAIL_BACKOFF_MS);
        scheduleReconnect(gen);
    }

    private void onAuthOk(boolean admin) {
        authenticated = true;
        relayAdmin = admin;
        // Remember it for next session's command tree, which is built before we get here. Only
        // write when it actually changed, so a normal connect doesn't touch the config file.
        TeamConfig config = TeamLocatorClient.CONFIG;
        if (config.wasRelayAdmin != admin) {
            config.wasRelayAdmin = admin;
            config.save();
            if (admin) {
                TeamLocatorConstants.LOGGER.info(
                        "Relay reports this account as an administrator; /relay is available "
                                + "after the next world join");
            }
        }
        // Back to a snappy retry now the relay is known good — unless Mojang rate-limited us
        // recently. A connection that authenticates then drops seconds later (relay restarting, bad
        // link) would otherwise restart the 1s/2s/4s ladder and spend every rung inside authlib's
        // ~15s joinServer window, which is exactly the loop that produced the reports.
        backoffMs = rateLimitedRecently() ? RATE_LIMIT_BACKOFF_MS : 1_000L;
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
                        dim != null ? dim : Identifier.of("minecraft:overworld"),
                        parseArmor(e),
                        // Absent from an older client, and from a relay that predates health.
                        e.has("health") ? e.get("health").getAsFloat()
                                : TrackedPos.UNKNOWN_HEALTH,
                        e.has("pingColor") && !e.get("pingColor").isJsonNull()
                                ? e.get("pingColor").getAsString() : null));
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

    /**
     * The relay asking, on another player's behalf, whether we would display their alert right
     * now. Evaluated silently with the exact {@link PingHandler#wouldDisplay} gate a real ping
     * would face — no toast, no sound — and answered with a yes/no. Runs on the executor because
     * the known-server check may do DNS, which must not stall the WebSocket receive thread.
     */
    private void onAvailabilityProbe(JsonObject obj, long gen) {
        if (!obj.has("probeId") || !obj.has("attacker")) {
            return;
        }
        final String probeId = obj.get("probeId").getAsString();
        final UUID attacker;
        try {
            attacker = UUID.fromString(obj.get("attacker").getAsString());
        } catch (IllegalArgumentException e) {
            return;
        }
        final String fromServer = obj.has("mcServer")
                ? obj.get("mcServer").getAsString() : mcServerKey;
        executor.execute(() -> {
            if (gen != attempt.get()) {
                return; // superseded socket; a reply would carry a stale probeId anyway
            }
            boolean available;
            try {
                available = PingHandler.wouldDisplay(attacker, fromServer, mcServerKey);
            } catch (RuntimeException e) {
                TeamLocatorConstants.LOGGER.debug("Availability probe evaluation failed: {}",
                        e.toString());
                available = false; // when in doubt, never promise a screen we can't verify
            }
            JsonObject r = new JsonObject();
            r.addProperty("type", "availability-response");
            r.addProperty("probeId", probeId);
            r.addProperty("available", available);
            sendJson(r);
        });
    }

    /** The relay's aggregate answer to our own {@link #queryAvailability}. */
    private void onAvailabilityResult(JsonObject obj) {
        List<UUID> receivers = new ArrayList<>();
        if (obj.has("receivers") && obj.get("receivers").isJsonArray()) {
            for (var el : obj.getAsJsonArray("receivers")) {
                try {
                    receivers.add(UUID.fromString(el.getAsString()));
                } catch (RuntimeException ignored) {
                    // skip a malformed uuid rather than dropping the whole result
                }
            }
        }
        ScheduledFuture<?> timeout = availabilityTimeout;
        if (timeout != null) {
            timeout.cancel(false);
        }
        Consumer<List<UUID>> cb = availabilityCallback;
        availabilityCallback = null;
        if (cb != null) {
            cb.accept(receivers);
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
