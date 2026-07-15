package dev.spog.teamlocator.relay;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.spog.teamlocator.relay.auth.Verifier;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real {@link RelayServer} over real WebSockets with a stub verifier, and asserts the
 * security properties the mod depends on: a position never reaches a viewer the owner didn't share
 * with, pings require mutual trust, the block list suppresses pings, and players on different
 * Minecraft servers never see each other.
 */
class RelayRoutingTest {
    private static final Gson GSON = new Gson();

    /** Maps a profile name to a deterministic UUID, standing in for Mojang's verification. */
    private static final class StubVerifier implements Verifier {
        @Override
        public String newChallenge() {
            return "test-nonce";
        }

        @Override
        public UUID verify(String profileName, String serverId, InetAddress ip) {
            return uuidOf(profileName);
        }
    }

    static UUID uuidOf(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes());
    }

    private RelayServer server;
    private int port;
    private final List<TestClient> clients = new ArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        server = new RelayServer(new InetSocketAddress("127.0.0.1", 0), new StubVerifier());
        server.start();
        // WebSocketServer binds asynchronously; wait for the ephemeral port to be assigned.
        for (int i = 0; i < 100 && server.getPort() <= 0; i++) {
            Thread.sleep(20);
        }
        port = server.getPort();
        assertTrue(port > 0, "relay did not bind");
    }

    @AfterEach
    void stopServer() throws Exception {
        for (TestClient c : clients) {
            c.closeBlocking();
        }
        clients.clear();
        server.stop(500);
    }

    /** A simulated modded client: connects, authenticates, and records what the relay sends it. */
    private final class TestClient extends WebSocketClient {
        final String name;
        final UUID uuid;
        private final CountDownLatch authed = new CountDownLatch(1);
        final List<JsonObject> snapshots = new ArrayList<>();
        final List<String> pings = new ArrayList<>();
        final List<String> pingServers = new ArrayList<>();
        private final Map<String, CountDownLatch> waiters = new ConcurrentHashMap<>();

        TestClient(String name, String mcServer) throws Exception {
            super(new URI("ws://127.0.0.1:" + port));
            this.name = name;
            this.uuid = uuidOf(name);
            this.mcServer = mcServer;
        }

        private final String mcServer;

        @Override
        public void onOpen(ServerHandshake handshake) {
            JsonObject hello = new JsonObject();
            hello.addProperty("type", "hello");
            hello.addProperty("protocolVersion", 1);
            hello.addProperty("mcServer", mcServer);
            hello.addProperty("profileName", name);
            send(GSON.toJson(hello));
        }

        @Override
        public void onMessage(String message) {
            JsonObject obj = GSON.fromJson(message, JsonObject.class);
            String type = obj.get("type").getAsString();
            switch (type) {
                case "auth-challenge" -> {
                    JsonObject resp = new JsonObject();
                    resp.addProperty("type", "auth-response");
                    send(GSON.toJson(resp));
                }
                case "auth-ok" -> authed.countDown();
                case "position-snapshot" -> {
                    synchronized (snapshots) {
                        snapshots.add(obj);
                    }
                    release("snapshot");
                }
                case "ping-broadcast" -> {
                    synchronized (pings) {
                        pings.add(obj.get("attacker").getAsString());
                        pingServers.add(obj.has("mcServer") ? obj.get("mcServer").getAsString() : null);
                    }
                    release("ping");
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

        void awaitAuth() throws InterruptedException {
            assertTrue(authed.await(5, TimeUnit.SECONDS), name + " failed to authenticate");
        }

        /** Arm a latch before triggering an action, so we can wait for the resulting frame. */
        CountDownLatch expect(String kind) {
            CountDownLatch latch = new CountDownLatch(1);
            waiters.put(kind, latch);
            return latch;
        }

        private void release(String kind) {
            CountDownLatch latch = waiters.remove(kind);
            if (latch != null) {
                latch.countDown();
            }
        }

        void sendTrust(String... names) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "trust-update");
            var arr = new com.google.gson.JsonArray();
            for (String n : names) {
                arr.add(uuidOf(n).toString());
            }
            o.add("sharingWith", arr);
            send(GSON.toJson(o));
        }

        void sendBlocked(String... names) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "block-update");
            var arr = new com.google.gson.JsonArray();
            for (String n : names) {
                arr.add(uuidOf(n).toString());
            }
            o.add("blocked", arr);
            send(GSON.toJson(o));
        }

        void sendPosition(double x, double y, double z, String dim) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "position-update");
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            o.addProperty("dimension", dim);
            send(GSON.toJson(o));
        }

        void sendAttackPing() {
            JsonObject o = new JsonObject();
            o.addProperty("type", "ping");
            send(GSON.toJson(o));
        }

        boolean sawPositionOf(TestClient other) {
            synchronized (snapshots) {
                return snapshots.stream().anyMatch(s ->
                        s.getAsJsonArray("entries").asList().stream().anyMatch(e ->
                                e.getAsJsonObject().get("id").getAsString()
                                        .equals(other.uuid.toString())));
            }
        }

        boolean sawPingFrom(TestClient other) {
            synchronized (pings) {
                return pings.contains(other.uuid.toString());
            }
        }
    }

    private TestClient connect(String name, String mcServer) throws Exception {
        TestClient c = new TestClient(name, mcServer);
        clients.add(c);
        assertTrue(c.connectBlocking(5, TimeUnit.SECONDS), "connect failed for " + name);
        c.awaitAuth();
        return c;
    }

    /** Give the relay a moment to fan out a frame that we expect NOT to arrive. */
    private static void settle() throws InterruptedException {
        Thread.sleep(300);
    }

    @Test
    void positionReachesOnlyPlayersTheOwnerSharesWith() throws Exception {
        TestClient alice = connect("alice", "play.example.net");
        TestClient bob = connect("bob", "play.example.net");
        TestClient mallory = connect("mallory", "play.example.net");

        // Alice shares only with Bob. Mallory is trusted by nobody.
        alice.sendTrust("bob");
        settle();

        CountDownLatch bobGetsIt = bob.expect("snapshot");
        alice.sendPosition(100, 64, -200, "minecraft:overworld");
        assertTrue(bobGetsIt.await(5, TimeUnit.SECONDS), "Bob should receive Alice's position");

        assertTrue(bob.sawPositionOf(alice), "Bob is trusted and must see Alice");
        assertFalse(mallory.sawPositionOf(alice), "Mallory is NOT trusted and must never see Alice");
    }

    @Test
    void positionIsNotSentBeforeAnyTrustIsDeclared() throws Exception {
        TestClient alice = connect("alice", "play.example.net");
        TestClient bob = connect("bob", "play.example.net");

        // No trust-update at all: Alice shares with nobody.
        alice.sendPosition(1, 2, 3, "minecraft:overworld");
        settle();

        assertFalse(bob.sawPositionOf(alice), "no trust declared, so no position may be routed");
    }

    @Test
    void pingRequiresMutualTrust() throws Exception {
        TestClient alice = connect("alice", "play.example.net");
        TestClient bob = connect("bob", "play.example.net");
        TestClient carol = connect("carol", "play.example.net");

        // Alice <-> Bob is mutual. Alice -> Carol is one-way (Carol does not trust Alice back).
        alice.sendTrust("bob", "carol");
        bob.sendTrust("alice");
        carol.sendTrust(); // trusts nobody
        settle();

        CountDownLatch bobPing = bob.expect("ping");
        alice.sendAttackPing();
        assertTrue(bobPing.await(5, TimeUnit.SECONDS), "Bob mutually trusts Alice and must get the ping");

        assertTrue(bob.sawPingFrom(alice));
        assertFalse(carol.sawPingFrom(alice), "one-way trust must NOT deliver a ping");
    }

    @Test
    void blockListSuppressesPings() throws Exception {
        TestClient alice = connect("alice", "play.example.net");
        TestClient bob = connect("bob", "play.example.net");

        // Mutual trust, but Bob has blocked Alice's pings.
        alice.sendTrust("bob");
        bob.sendTrust("alice");
        bob.sendBlocked("alice");
        settle();

        alice.sendAttackPing();
        settle();

        assertFalse(bob.sawPingFrom(alice), "a blocked sender's ping must be dropped");
    }

    @Test
    void blockedPlayerStillSeesCoordinatesTheyAreTrustedWith() throws Exception {
        // Blocking only suppresses pings (anti-spam); it is not a coordinate-sharing revocation.
        TestClient alice = connect("alice", "play.example.net");
        TestClient bob = connect("bob", "play.example.net");

        alice.sendTrust("bob");
        bob.sendBlocked("alice");
        settle();

        CountDownLatch got = bob.expect("snapshot");
        alice.sendPosition(5, 6, 7, "minecraft:overworld");
        assertTrue(got.await(5, TimeUnit.SECONDS));
        assertTrue(bob.sawPositionOf(alice), "block affects pings only, not coordinate sharing");
    }

    @Test
    void positionsNeverCrossMinecraftServers() throws Exception {
        TestClient alice = connect("alice", "play.example.net");
        TestClient bob = connect("bob", "other.example.net");

        // Full mutual trust, but they are on different Minecraft servers.
        alice.sendTrust("bob");
        bob.sendTrust("alice");
        settle();

        alice.sendPosition(10, 20, 30, "minecraft:overworld");
        settle();

        assertFalse(bob.sawPositionOf(alice), "cross-server position leak");
    }

    @Test
    void pingCrossesServersAndCarriesTheAttackersServer() throws Exception {
        TestClient alice = connect("alice", "play.example.net");
        TestClient bob = connect("bob", "other.example.net");
        TestClient menuFriend = connect("carol", "menu");
        TestClient stranger = connect("mallory", "other.example.net");

        // Alice mutually trusts Bob (another server) and Carol (sitting at the main menu).
        alice.sendTrust("bob", "carol");
        bob.sendTrust("alice");
        menuFriend.sendTrust("alice");
        settle();

        CountDownLatch bobPing = bob.expect("ping");
        CountDownLatch carolPing = menuFriend.expect("ping");
        alice.sendAttackPing();
        assertTrue(bobPing.await(5, TimeUnit.SECONDS), "ping must reach a teammate on another server");
        assertTrue(carolPing.await(5, TimeUnit.SECONDS), "ping must reach a teammate at the menu");

        assertTrue(bob.sawPingFrom(alice));
        assertTrue(menuFriend.sawPingFrom(alice));
        assertFalse(stranger.sawPingFrom(alice), "no mutual trust, no ping — any scope");
        synchronized (bob.pings) {
            assertEquals("play.example.net", bob.pingServers.get(0),
                    "broadcast must name the attacker's server");
        }
    }

    @Test
    void unauthenticatedSocketCannotInjectTrustOrReadPositions() throws Exception {
        TestClient alice = connect("alice", "play.example.net");

        // A raw socket that never completes the handshake.
        var rogue = new WebSocketClient(new URI("ws://127.0.0.1:" + port)) {
            final List<String> received = new ArrayList<>();

            @Override
            public void onOpen(ServerHandshake h) {
                // Skip hello/auth entirely and try to act as if authenticated.
                JsonObject o = new JsonObject();
                o.addProperty("type", "trust-update");
                var arr = new com.google.gson.JsonArray();
                arr.add(uuidOf("alice").toString());
                o.add("sharingWith", arr);
                send(GSON.toJson(o));
            }

            @Override
            public void onMessage(String message) {
                synchronized (received) {
                    received.add(message);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
            }

            @Override
            public void onError(Exception ex) {
            }
        };
        assertTrue(rogue.connectBlocking(5, TimeUnit.SECONDS));

        alice.sendTrust("alice"); // irrelevant; just drive some traffic
        alice.sendPosition(1, 1, 1, "minecraft:overworld");
        settle();

        synchronized (rogue.received) {
            assertTrue(rogue.received.stream().noneMatch(m -> m.contains("position-snapshot")),
                    "an unauthenticated socket must never receive positions");
        }
        rogue.closeBlocking();
    }

    @Test
    void dimensionIsRelayedVerbatim() throws Exception {
        TestClient alice = connect("alice", "play.example.net");
        TestClient bob = connect("bob", "play.example.net");
        alice.sendTrust("bob");
        settle();

        CountDownLatch got = bob.expect("snapshot");
        alice.sendPosition(1, 2, 3, "minecraft:the_nether");
        assertTrue(got.await(5, TimeUnit.SECONDS));

        synchronized (bob.snapshots) {
            JsonObject entry = bob.snapshots.get(bob.snapshots.size() - 1)
                    .getAsJsonArray("entries").get(0).getAsJsonObject();
            assertEquals("minecraft:the_nether", entry.get("dimension").getAsString());
            assertEquals(1.0, entry.get("x").getAsDouble());
        }
    }
}
