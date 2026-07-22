package dev.spog.teamlocator.relay;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.spog.teamlocator.relay.auth.Verifier;
import dev.spog.teamlocator.relay.protocol.Messages;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relay must serve clients whose protocol version differs from its own, so an older mod keeps
 * working after the relay is updated (and a not-yet-updated relay does not turn away a newer mod).
 * This is the whole point of {@link Messages#MIN_SUPPORTED_VERSION}: a version bump alone must never
 * strand players who haven't updated yet.
 *
 * <p>These drive the real {@link RelayServer} over real sockets, sending {@code hello} frames that
 * claim various versions and asserting the connection is accepted (or refused) accordingly, and —
 * crucially — that a genuinely old-shaped client (v1: {@code sharingWith} only, no armor) still gets
 * routed correctly through the relay's additive fallbacks.
 */
class CrossVersionCompatibilityTest {
    private static final Gson GSON = new Gson();

    private static final class StubVerifier implements Verifier {
        @Override
        public String newChallenge() {
            return "test-nonce";
        }

        @Override
        public UUID verify(String profileName, String serverId, InetAddress ip) {
            return UUID.nameUUIDFromBytes(profileName.getBytes());
        }
    }

    private static UUID uuidOf(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes());
    }

    private RelayServer server;
    private int port;
    private final List<VersionedClient> clients = new ArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        server = new RelayServer(new InetSocketAddress("127.0.0.1", 0), new StubVerifier());
        server.start();
        for (int i = 0; i < 100 && server.getPort() <= 0; i++) {
            Thread.sleep(20);
        }
        port = server.getPort();
        assertTrue(port > 0, "relay did not bind");
    }

    @AfterEach
    void stopServer() throws Exception {
        for (VersionedClient c : clients) {
            c.closeBlocking();
        }
        clients.clear();
        server.stop(500);
    }

    /**
     * A client that claims an arbitrary protocol version in its hello, and — when {@code legacy} —
     * behaves like the v1 mod: trust is a single {@code sharingWith} set with no {@code alertsWith},
     * and it never sends armor. That exercises the relay's additive fallbacks, not just the version
     * gate.
     */
    private final class VersionedClient extends WebSocketClient {
        final String name;
        final UUID uuid;
        private final int claimedVersion;
        private final String mcServer;
        private final CountDownLatch authed = new CountDownLatch(1);
        private final CountDownLatch failed = new CountDownLatch(1);
        volatile String failReason;
        final List<JsonObject> snapshots = new ArrayList<>();
        private volatile CountDownLatch snapshotWaiter;

        VersionedClient(String name, String mcServer, int claimedVersion) throws Exception {
            super(new URI("ws://127.0.0.1:" + port));
            this.name = name;
            this.uuid = uuidOf(name);
            this.mcServer = mcServer;
            this.claimedVersion = claimedVersion;
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            JsonObject hello = new JsonObject();
            hello.addProperty("type", "hello");
            hello.addProperty("protocolVersion", claimedVersion);
            hello.addProperty("mcServer", mcServer);
            hello.addProperty("profileName", name);
            send(GSON.toJson(hello));
        }

        @Override
        public void onMessage(String message) {
            JsonObject obj = GSON.fromJson(message, JsonObject.class);
            switch (obj.get("type").getAsString()) {
                case "auth-challenge" -> {
                    JsonObject resp = new JsonObject();
                    resp.addProperty("type", "auth-response");
                    send(GSON.toJson(resp));
                }
                case "auth-ok" -> authed.countDown();
                case "auth-fail" -> {
                    failReason = obj.has("reason") ? obj.get("reason").getAsString() : "?";
                    failed.countDown();
                }
                case "position-snapshot" -> {
                    synchronized (snapshots) {
                        snapshots.add(obj);
                    }
                    CountDownLatch w = snapshotWaiter;
                    if (w != null) {
                        w.countDown();
                    }
                }
                default -> { }
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
        }

        @Override
        public void onError(Exception ex) {
        }

        boolean awaitAuth() throws InterruptedException {
            return authed.await(5, TimeUnit.SECONDS);
        }

        boolean awaitFail() throws InterruptedException {
            return failed.await(5, TimeUnit.SECONDS);
        }

        CountDownLatch expectSnapshot() {
            CountDownLatch latch = new CountDownLatch(1);
            snapshotWaiter = latch;
            return latch;
        }

        /** v1 shape: a single sharing set, no alertsWith. */
        void sendLegacyTrust(String... names) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "trust-update");
            var arr = new com.google.gson.JsonArray();
            for (String n : names) {
                arr.add(uuidOf(n).toString());
            }
            o.add("sharingWith", arr);
            send(GSON.toJson(o));
        }

        void sendPosition(double x, double y, double z) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "position-update");
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            o.addProperty("dimension", "minecraft:overworld");
            send(GSON.toJson(o));
        }

        boolean sawPositionOf(VersionedClient other) {
            synchronized (snapshots) {
                return snapshots.stream().anyMatch(s ->
                        s.getAsJsonArray("entries").asList().stream().anyMatch(e ->
                                e.getAsJsonObject().get("id").getAsString()
                                        .equals(other.uuid.toString())));
            }
        }
    }

    private VersionedClient connect(String name, String mcServer, int version) throws Exception {
        VersionedClient c = new VersionedClient(name, mcServer, version);
        clients.add(c);
        assertTrue(c.connectBlocking(5, TimeUnit.SECONDS), "connect failed for " + name);
        return c;
    }

    @Test
    void theOldestSupportedVersionIsAccepted() throws Exception {
        VersionedClient old = connect("old", "play.example.net", Messages.MIN_SUPPORTED_VERSION);
        assertTrue(old.awaitAuth(),
                "a client at the supported floor (v" + Messages.MIN_SUPPORTED_VERSION
                        + ") must authenticate, not be rejected");
    }

    @Test
    void aVersionBelowTheFloorIsRefusedWithAClearReason() throws Exception {
        VersionedClient tooOld = connect("ancient", "play.example.net",
                Messages.MIN_SUPPORTED_VERSION - 1);
        assertTrue(tooOld.awaitFail(), "a sub-floor client must be told why, not left hanging");
        assertTrue(tooOld.failReason != null && tooOld.failReason.contains("too old"),
                "the rejection must name the version problem, got: " + tooOld.failReason);
    }

    @Test
    void aNewerClientThanTheRelayIsAcceptedNotRejected() throws Exception {
        // Forward-compat: someone updates their mod before the relay is updated. The relay must
        // serve them (clamped to its own version), never turn them away.
        VersionedClient future = connect("future", "play.example.net",
                Messages.PROTOCOL_VERSION + 5);
        assertTrue(future.awaitAuth(),
                "a client newer than the relay must be accepted, degraded to the relay's version");
    }

    @Test
    void aLegacyClientIsRoutedThroughTheRelaysAdditiveFallbacks() throws Exception {
        // The real test of cross-version support: not just that an old client connects, but that
        // it actually works. A v1 client sends sharingWith with no alertsWith and no armor; the
        // relay must still route its position to a trusted viewer.
        VersionedClient legacy = connect("legacy", "play.example.net",
                Messages.MIN_SUPPORTED_VERSION);
        VersionedClient current = connect("current", "play.example.net", Messages.PROTOCOL_VERSION);
        assertTrue(legacy.awaitAuth());
        assertTrue(current.awaitAuth());

        legacy.sendLegacyTrust("current");
        Thread.sleep(300);

        CountDownLatch got = current.expectSnapshot();
        legacy.sendPosition(100, 64, -200);
        assertTrue(got.await(5, TimeUnit.SECONDS),
                "a v1-shaped client's position must still reach a trusted current-version viewer");
        assertTrue(current.sawPositionOf(legacy), "the legacy client must be visible to the viewer");
    }

    @Test
    void aMissingVersionFieldIsTreatedAsBelowTheFloorAndRefused() throws Exception {
        // A hello with no protocolVersion at all reads as 0, which is below the floor. This guards
        // the boundary: "absent" must not accidentally be treated as "current".
        var raw = new WebSocketClient(new URI("ws://127.0.0.1:" + port)) {
            final CountDownLatch failed = new CountDownLatch(1);
            volatile String reason;

            @Override
            public void onOpen(ServerHandshake h) {
                JsonObject hello = new JsonObject();
                hello.addProperty("type", "hello");
                // deliberately no protocolVersion
                hello.addProperty("mcServer", "play.example.net");
                hello.addProperty("profileName", "noversion");
                send(GSON.toJson(hello));
            }

            @Override
            public void onMessage(String message) {
                JsonObject obj = GSON.fromJson(message, JsonObject.class);
                if ("auth-fail".equals(obj.get("type").getAsString())) {
                    reason = obj.has("reason") ? obj.get("reason").getAsString() : "?";
                    failed.countDown();
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
            }

            @Override
            public void onError(Exception ex) {
            }
        };
        assertTrue(raw.connectBlocking(5, TimeUnit.SECONDS));
        assertTrue(raw.failed.await(5, TimeUnit.SECONDS),
                "a hello without a version must be refused, not silently accepted as current");
        raw.closeBlocking();
    }
}
