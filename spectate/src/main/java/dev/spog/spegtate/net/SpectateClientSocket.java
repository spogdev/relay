package dev.spog.spegtate.net;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.spog.spegtate.SpectateClient;
import dev.spog.spegtate.SpectateRoster;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The addon's socket to the spectate service.
 *
 * <p>Separate from Relay's own socket because the two carry very different traffic: Relay's is a
 * JSON control channel where an attack alert must arrive promptly, this one streams terrain in
 * multi-hundred-kilobyte bursts. Sharing a socket would queue alerts behind chunk data, and the JDK
 * WebSocket's one-send-at-a-time rule would make that queueing strict.
 *
 * <p>This socket is opened lazily -- only when a spectate session is wanted or the client may be a
 * target -- and closed when neither applies, so a user who never spectates never pays for it.
 *
 * <p><b>Mojang rate limiting.</b> authlib shares a single joinServer limiter between this mod,
 * Relay and vanilla's own server joins. A refused call here does not just fail this socket: it
 * re-arms the limiter while the player may be trying to join a real server. So authentication is
 * attempted once per connect, failures back off hard, and the socket stays down rather than
 * retrying aggressively -- spectating is optional, and the player's ability to join servers is not.
 */
public final class SpectateClientSocket {
    /** Give up on a connect attempt that has not authenticated in this long. */
    private static final long CONNECT_TIMEOUT_MS = 20_000L;

    /** After a failed authentication, wait at least this long before trying again. */
    private static final long AUTH_BACKOFF_MS = 60_000L;

    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spegtate-net");
        t.setDaemon(true);
        return t;
    });

    private final String url;
    private final Consumer<ByteBuffer> onBinary;

    private volatile WebSocket socket;
    private volatile boolean authenticated;
    private volatile long nextAttemptAllowedMs;

    /** Serializes sends; the JDK WebSocket rejects overlapping send operations. */
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    /**
     * Frames handed to {@link #sendChain} that have not finished sending.
     *
     * <p>The chain is strictly sequential, so this is the depth of the queue behind the frame
     * currently on the wire. It exists because the tick loop's rate limit counted frames handed
     * off rather than frames sent: while swept chunks carried no light they were small enough
     * that the difference never showed, and once they carried real light the chain fell behind
     * and live packets queued behind several seconds of terrain.
     */
    private final java.util.concurrent.atomic.AtomicInteger framesInFlight =
            new java.util.concurrent.atomic.AtomicInteger();

    /** How many frames are waiting on the send chain. */
    public int framesInFlight() {
        return framesInFlight.get();
    }

    /** Counts failed frame sends, so a burst reports once rather than per frame. */
    private final java.util.concurrent.atomic.AtomicLong sendFailures =
            new java.util.concurrent.atomic.AtomicLong();

    public SpectateClientSocket(String url, Consumer<ByteBuffer> onBinary) {
        this.url = url;
        this.onBinary = onBinary;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public boolean isConnected() {
        WebSocket ws = socket;
        return ws != null && !ws.isInputClosed();
    }

    /** Opens the socket if it is down and the backoff has expired. */
    public void ensureConnected() {
        if (isConnected() || System.currentTimeMillis() < nextAttemptAllowedMs) {
            return;
        }
        connect();
    }

    private void connect() {
        // Reserve the backoff window up front: a connect that dies before authenticating must not
        // let the next tick immediately try again.
        nextAttemptAllowedMs = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
        try {
            HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .buildAsync(URI.create(url), new Listener())
                    .whenComplete((ws, error) -> {
                        if (error != null) {
                            SpectateClient.LOGGER.debug("spectate socket failed: {}",
                                    error.toString());
                            return;
                        }
                        socket = ws;
                        sendHello();
                    });
        } catch (RuntimeException e) {
            SpectateClient.LOGGER.debug("spectate socket could not start", e);
        }
    }

    private void sendHello() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("version", SpectateProtocol.VERSION);
        // The Minecraft version, so the service can refuse to pair two clients that cannot
        // understand each other's packets. See SpectateProtocol.DENY_VERSION.
        hello.addProperty("gameVersion", net.minecraft.SharedConstants.getCurrentVersion().name());
        hello.addProperty("profileName", mc.getUser().getName());
        sendText(gson.toJson(hello));
    }

    /**
     * Answers the service's auth challenge.
     *
     * <p>The joinServer call is blocking HTTP and shares Mojang's limiter with vanilla, so it goes
     * on this socket's own executor and never on the receive thread.
     */
    private void onChallenge(String serverId) {
        executor.execute(() -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                mc.services().sessionService().joinServer(
                        mc.getUser().getProfileId(), mc.getUser().getAccessToken(), serverId);
                JsonObject response = new JsonObject();
                response.addProperty("type", "auth-response");
                sendText(gson.toJson(response));
            } catch (Exception e) {
                // Back off hard rather than retrying: the shared limiter means a retry storm here
                // would interfere with the player joining real servers.
                SpectateClient.LOGGER.debug("spectate auth failed, backing off: {}", e.toString());
                nextAttemptAllowedMs = System.currentTimeMillis() + AUTH_BACKOFF_MS;
                close();
            }
        });
    }

    /**
     * Tells the service whether this client is accepting viewers.
     *
     * <p>A single bit rather than a list of permitted UUIDs: the mod is private and everyone
     * holding it has agreed the others may watch them, so the only question is consent.
     */
    public void sendConsent(boolean allowsViewers) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "trust-update");
        obj.addProperty("allowsViewers", allowsViewers);
        sendText(gson.toJson(obj));
    }


    /**
     * Reports the players near the target, so a viewer can move them every tick.
     *
     * <p>One frame for all of them rather than one each: at 20 Hz with a handful of players in
     * view, separate frames would multiply the session's frame count for no gain, and they are
     * all read in the same tick at the other end anyway.
     */
    public void sendPeerPositions(java.util.List<double[]> peers) {
        int count = Math.min(peers.size(), SpectateProtocol.MAX_PEER_POSITIONS);
        if (count == 0) {
            return;
        }
        ByteBuffer buf = ByteBuffer.allocate(1 + 2 + count * (4 + 8 * 3 + 4 * 3 + 1));
        buf.put(SpectateProtocol.TYPE_PEER_POSITIONS);
        buf.putShort((short) count);
        for (int i = 0; i < count; i++) {
            double[] peer = peers.get(i);
            buf.putInt((int) peer[0]);
            buf.putDouble(peer[1]).putDouble(peer[2]).putDouble(peer[3]);
            buf.putFloat((float) peer[4]).putFloat((float) peer[5]).putFloat((float) peer[6]);
            buf.put((byte) (peer[7] != 0 ? 1 : 0));
        }
        sendBinary(buf.flip());
    }

    /** Asks to begin watching a target. */
    public void requestSession(UUID target) {
        sendBinary(Frames.of(SpectateProtocol.TYPE_SESSION_REQUEST, target));
    }

    /** Ends the session this client is viewing. */
    public void stopSession() {
        sendBinary(Frames.of(SpectateProtocol.TYPE_SESSION_STOP));
    }

    /** Sends one captured game packet to whoever is watching. */
    public void sendGamePacket(byte[] wire) {
        sendBinary(Frames.of(SpectateProtocol.TYPE_GAME_PACKET, wire));
    }

    /** Sends the recorded handshake as one frame per packet. */
    public void sendHandshake(List<byte[]> packets) {
        for (byte[] packet : packets) {
            sendBinary(Frames.of(SpectateProtocol.TYPE_HANDSHAKE, packet));
        }
    }

    /**
     * Sends the target's login packet, which completes the handshake.
     *
     * <p>Its own frame type because it is encoded with the game codec while the rest of the
     * handshake uses the configuration codec; the receiver must know which to decode with.
     */
    public void sendLoginPacket(byte[] wire) {
        sendBinary(Frames.of(SpectateProtocol.TYPE_LOGIN_PACKET, wire));
    }

    /**
     * Reports where the target is, so the viewer can follow it.
     *
     * <p>Sent on a timer because the target's own position is not in its inbound stream: a client
     * knows where it is because it moved itself there. Without this the viewer's chunk cache stays
     * centred on 0,0 and discards every chunk that arrives.
     */
    public void sendTargetPosition(double x, double y, double z, float yaw, float pitch,
                                   int entityId, float headYaw, boolean onGround,
                                   int swingAction, int pose) {
        ByteBuffer buf = ByteBuffer.allocate(1 + 8 * 3 + 4 * 2 + 4 + 4 + 1 + 1 + 1);
        buf.put(SpectateProtocol.TYPE_TARGET_POSITION);
        buf.putDouble(x).putDouble(y).putDouble(z).putFloat(yaw).putFloat(pitch);
        // The entity id, head rotation and ground flag were added so the viewer can move the
        // target's own entity. A client's position never appears in its own inbound stream -- it
        // moved itself there -- so the target's entity stood still in the viewer's world while
        // every other entity moved normally. The viewer turns each report into a position-sync
        // packet for this id.
        buf.putInt(entityId).putFloat(headYaw).put((byte) (onGround ? 1 : 0));
        // The swing action is appended as a further optional field, for the same reason as the
        // fields above: an arm swing is an animation the server tells other clients about, so it
        // is absent from the target's own inbound stream and the target's arms never moved. -1
        // means "not swinging"; anything else is a ClientboundAnimatePacket action id.
        buf.put((byte) swingAction);
        // The pose, appended as one more optional field. A client is never told its
        // own metadata, so without this the target's sneak reached a viewer only by
        // the server echoing it to the players around the target -- a full round
        // trip, which is the delay.
        buf.put((byte) pose);
        sendBinary(buf.flip());
    }

    /** Tells the service this client has no world to share, so waiting viewers can be denied. */
    /**
     * Asks the target to resend chunks this viewer is missing.
     *
     * <p>The one frame a viewer sends into a session. See
     * {@link SpectateProtocol#TYPE_CHUNK_REQUEST} for why repair is needed at all: a chunk is sent
     * once, so a chunk lost anywhere on the path is lost permanently unless it is asked for again.
     */
    public void sendChunkRequest(java.util.List<net.minecraft.world.level.ChunkPos> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        int count = Math.min(chunks.size(), SpectateProtocol.MAX_CHUNK_REQUESTS);
        ByteBuffer buf = ByteBuffer.allocate(1 + 2 + count * 8);
        buf.put(SpectateProtocol.TYPE_CHUNK_REQUEST);
        buf.putShort((short) count);
        for (int i = 0; i < count; i++) {
            buf.putInt(chunks.get(i).x()).putInt(chunks.get(i).z());
        }
        sendBinary(buf.flip());
    }

    /**
     * Asks the target to send its surroundings again from scratch.
     *
     * <p>For a viewer that has no terrain where it is going, rather than one with holes in it; see
     * {@link SpectateProtocol#TYPE_RESWEEP_REQUEST}.
     */
    public void sendResweepRequest() {
        sendBinary(Frames.of(SpectateProtocol.TYPE_RESWEEP_REQUEST));
    }

    public void sendCaptureUnavailable() {
        sendBinary(Frames.of(SpectateProtocol.TYPE_CAPTURE_UNAVAILABLE));
    }

    /** Signals that this client has stopped streaming. */
    public void sendStreamEnd() {
        sendBinary(Frames.of(SpectateProtocol.TYPE_STREAM_END));
    }

    private void sendText(String text) {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        synchronized (this) {
            sendChain = sendChain.thenCompose(ignored -> {
                WebSocket current = socket;
                return current == null ? CompletableFuture.completedFuture(null)
                        : current.sendText(text, true);
            }).exceptionally(e -> null);
        }
    }

    private void sendBinary(ByteBuffer frame) {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        // The payload is snapshotted here rather than inside the lambda. The lambda runs when the
        // previous send completes, which for a burst is long after this call returned, and a
        // ByteBuffer's position and limit are mutable state -- so reading it later is a race with
        // whatever else touches it in the meantime.
        ByteBuffer payload = frame.duplicate();
        framesInFlight.incrementAndGet();
        synchronized (this) {
            sendChain = sendChain.thenCompose(ignored -> {
                WebSocket current = socket;
                return current == null ? CompletableFuture.completedFuture(null)
                        : current.sendBinary(payload.duplicate(), true);
            }).whenComplete((ignored, error) -> framesInFlight.decrementAndGet())
                    .exceptionally(e -> {
                // Counted and logged once per burst rather than per frame. Swallowing these
                // silently hid a complete terrain failure: the target reported "sent 421 chunks"
                // while the viewer received none and nothing anywhere said a send had failed.
                long n = sendFailures.incrementAndGet();
                if (n == 1 || n % 100 == 0) {
                    SpectateClient.LOGGER.warn("spectate: {} frame send(s) failed; latest", n,
                            e instanceof java.util.concurrent.CompletionException && e.getCause() != null
                                    ? e.getCause() : e);
                }
                return null;
                    });
        }
    }

    /** Closes the socket. Safe to call when already closed. */
    public void close() {
        WebSocket ws = socket;
        socket = null;
        authenticated = false;
        // Anything still chained will never complete now, so the count is reset rather than
        // left high -- a stale count would throttle the next session to a standstill.
        framesInFlight.set(0);
        SpectateRoster.clear();
        if (ws != null) {
            ws.abort();
        }
    }

    private final class Listener implements WebSocket.Listener {
        /** The JDK WebSocket may deliver a frame in fragments; accumulate until last. */
        private final StringBuilder text = new StringBuilder();
        private ByteBuffer binary;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String message = text.toString();
                text.setLength(0);
                handleText(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            if (binary == null) {
                binary = ByteBuffer.allocate(data.remaining()).put(data).flip();
            } else {
                ByteBuffer grown = ByteBuffer.allocate(binary.remaining() + data.remaining())
                        .put(binary).put(data).flip();
                binary = grown;
            }
            if (last) {
                ByteBuffer complete = binary;
                binary = null;
                onBinary.accept(complete);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            socket = null;
            authenticated = false;
            SpectateRoster.clear();
            SpectateClient.LOGGER.debug("spectate socket closed: {} {}", statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            socket = null;
            authenticated = false;
            SpectateRoster.clear();
            SpectateClient.LOGGER.debug("spectate socket error: {}", error.toString());
        }

        private void handleText(String message) {
            try {
                JsonObject obj = gson.fromJson(message, JsonObject.class);
                if (obj == null || !obj.has("type")) {
                    return;
                }
                switch (obj.get("type").getAsString()) {
                    case "auth-challenge" -> onChallenge(obj.get("serverId").getAsString());
                    case "auth-ok" -> {
                        authenticated = true;
                        nextAttemptAllowedMs = 0L;
                        SpectateClient.LOGGER.info("spectate service authenticated");
                    }
                    case "roster" -> {
                        if (obj.has("peers")) {
                            var peers = SpectateRoster.parse(obj.getAsJsonArray("peers"));
                            SpectateRoster.set(peers);
                            // Logged because an empty menu otherwise cannot be told apart from
                            // a service that never sent a roster at all -- which is the first
                            // question asked when the menu says nobody is online.
                            SpectateClient.LOGGER.info(
                                    "spectate roster: {} player(s) available", peers.size());
                        }
                    }
                    case "auth-fail" -> {
                        String reason = obj.has("reason") ? obj.get("reason").getAsString() : "?";
                        SpectateClient.LOGGER.warn("spectate auth rejected: {}", reason);
                        nextAttemptAllowedMs = System.currentTimeMillis() + AUTH_BACKOFF_MS;
                        close();
                    }
                    default -> { }
                }
            } catch (RuntimeException e) {
                SpectateClient.LOGGER.debug("malformed spectate control frame", e);
            }
        }
    }
}
