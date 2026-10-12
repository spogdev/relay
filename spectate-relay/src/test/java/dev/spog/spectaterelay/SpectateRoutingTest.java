package dev.spog.spectaterelay;

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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real {@link SpectateRelayServer} over real WebSockets with a stub verifier, and
 * asserts the properties the addon's privacy rests on: a stream only ever reaches a viewer the
 * target consents to, withdrawing consent cuts an established session immediately, and nobody can
 * spectate by asserting their own identity.
 *
 * <p>These are the tests worth having without the game, because they cover the part that decides
 * who sees whose world. The rendering path needs a running client; this does not.
 */
class SpectateRoutingTest {
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

    private SpectateRelayServer server;
    private int port;
    private final List<TestClient> clients = new ArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        server = new SpectateRelayServer(new InetSocketAddress("127.0.0.1", 0), new StubVerifier());
        server.start();
        // WebSocketServer binds asynchronously; wait for the ephemeral port to be assigned.
        for (int i = 0; i < 100 && server.getPort() <= 0; i++) {
            Thread.sleep(20);
        }
        port = server.getPort();
        assertTrue(port > 0, "service did not bind");
    }

    @AfterEach
    void stopServer() throws Exception {
        for (TestClient c : clients) {
            c.closeBlocking();
        }
        clients.clear();
        server.stop(500);
    }

    /** A simulated addon client: authenticates, then records the frames it is sent. */
    private final class TestClient extends WebSocketClient {
        final String name;
        final UUID uuid;
        private final CountDownLatch authed = new CountDownLatch(1);

        final List<byte[]> streamFrames = Collections.synchronizedList(new ArrayList<>());
        final List<Byte> controlTypes = Collections.synchronizedList(new ArrayList<>());
        final List<Byte> denyReasons = Collections.synchronizedList(new ArrayList<>());

        /** How many roster messages this client has been sent, and the size of the last. */
        final java.util.concurrent.atomic.AtomicInteger rosters =
                new java.util.concurrent.atomic.AtomicInteger();
        volatile int lastRosterSize = -1;

        /** The Minecraft version this client claims; two clients must match to be paired. */
        private final String gameVersion;

        TestClient(String name) throws Exception {
            this(name, "26.1.2");
        }

        TestClient(String name, String gameVersion) throws Exception {
            super(new URI("ws://127.0.0.1:" + port));
            this.name = name;
            this.uuid = uuidOf(name);
            this.gameVersion = gameVersion;
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            JsonObject hello = new JsonObject();
            hello.addProperty("type", "hello");
            // Track the real constant: a version bump should not silently fail every test with an
            // auth rejection, which is what a hardcoded number here produces.
            hello.addProperty("version", Wire.VERSION);
            hello.addProperty("gameVersion", gameVersion);
            hello.addProperty("profileName", name);
            send(GSON.toJson(hello));
        }

        @Override
        public void onMessage(String message) {
            JsonObject obj = GSON.fromJson(message, JsonObject.class);
            if (obj == null || !obj.has("type")) {
                return;
            }
            switch (obj.get("type").getAsString()) {
                case "auth-challenge" -> {
                    JsonObject response = new JsonObject();
                    response.addProperty("type", "auth-response");
                    send(GSON.toJson(response));
                }
                case "auth-ok" -> authed.countDown();
                case "roster" -> {
                    lastRosterSize = obj.getAsJsonArray("peers").size();
                    rosters.incrementAndGet();
                }
                default -> { }
            }
        }

        @Override
        public void onMessage(ByteBuffer frame) {
            byte type = Wire.typeOf(frame);
            controlTypes.add(type);
            switch (type) {
                case Wire.TYPE_SESSION_DENIED -> {
                    frame.get();
                    denyReasons.add(frame.get());
                }
                case Wire.TYPE_HANDSHAKE, Wire.TYPE_GAME_PACKET, Wire.TYPE_LOGIN_PACKET -> {
                    frame.get();
                    byte[] payload = new byte[frame.remaining()];
                    frame.get(payload);
                    streamFrames.add(payload);
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
            assertTrue(authed.await(5, TimeUnit.SECONDS), name + " did not authenticate");
        }

        void sendConsent(boolean allowsViewers) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "trust-update");
            obj.addProperty("allowsViewers", allowsViewers);
            send(GSON.toJson(obj));
        }

        void requestSession(UUID target) {
            send(Wire.of(Wire.TYPE_SESSION_REQUEST, target));
        }

        void sendGamePacket(byte[] payload) {
            ByteBuffer buf = ByteBuffer.allocate(1 + payload.length);
            buf.put(Wire.TYPE_GAME_PACKET).put(payload);
            send(buf.flip());
        }

        boolean sawType(byte type) {
            return controlTypes.contains(type);
        }
    }

    private TestClient connect(String name) throws Exception {
        return connect(name, "26.1.2");
    }

    private TestClient connect(String name, String gameVersion) throws Exception {
        TestClient client = new TestClient(name, gameVersion);
        clients.add(client);
        assertTrue(client.connectBlocking(5, TimeUnit.SECONDS), name + " did not connect");
        client.awaitAuth();
        return client;
    }

    /** Settle time for a round trip through the service. */
    private static void settle() throws InterruptedException {
        Thread.sleep(300);
    }

    @Test
    void streamReachesAConsentedViewer() throws Exception {
        TestClient target = connect("target");
        TestClient viewer = connect("viewer");
        target.sendConsent(true);
        settle();

        viewer.requestSession(target.uuid);
        settle();
        assertTrue(viewer.sawType(Wire.TYPE_SESSION_READY), "viewer was not given a session");
        assertTrue(target.sawType(Wire.TYPE_CAPTURE_START), "target was not asked to capture");

        target.sendGamePacket(new byte[] {1, 2, 3});
        settle();
        assertEquals(1, viewer.streamFrames.size(), "viewer did not receive the stream");
        assertArrayEqualsBytes(new byte[] {1, 2, 3}, viewer.streamFrames.get(0));
    }

    @Test
    void streamNeverReachesAViewerWhenConsentIsWithheld() throws Exception {
        TestClient target = connect("target");
        TestClient viewer = connect("viewer");
        // Target has spectating turned off.
        target.sendConsent(false);
        settle();

        viewer.requestSession(target.uuid);
        settle();
        assertFalse(viewer.sawType(Wire.TYPE_SESSION_READY), "refusing target was spectated anyway");
        assertTrue(viewer.denyReasons.contains(Wire.DENY_NOT_TRUSTED), "no refusal");
        assertFalse(target.sawType(Wire.TYPE_CAPTURE_START), "target was asked to capture anyway");

        target.sendGamePacket(new byte[] {9});
        settle();
        assertTrue(viewer.streamFrames.isEmpty(), "stream leaked from a refusing target");
    }

    @Test
    void aTargetThatHasNotReportedConsentIsNotSpectatable() throws Exception {
        // The default matters: a client that has authenticated but not yet sent its consent must
        // not be watchable in that window. Nothing here sends a consent frame at all.
        TestClient target = connect("target");
        TestClient viewer = connect("viewer");
        settle();

        viewer.requestSession(target.uuid);
        settle();
        assertFalse(viewer.sawType(Wire.TYPE_SESSION_READY), "silent target was spectated");
        assertTrue(viewer.denyReasons.contains(Wire.DENY_NOT_TRUSTED), "no refusal");
    }

    @Test
    void anyConsentingClientMayBeWatchedWithoutBeingAddedToAList() throws Exception {
        // The point of the change: the target never names the viewer, and the viewer is a
        // stranger to it. Consent alone is enough.
        TestClient target = connect("target");
        TestClient viewer = connect("viewer");
        target.sendConsent(true);
        settle();

        viewer.requestSession(target.uuid);
        settle();
        assertTrue(viewer.sawType(Wire.TYPE_SESSION_READY), "a consenting target refused a stranger");
        assertTrue(target.sawType(Wire.TYPE_CAPTURE_START), "target was not asked to capture");
    }

    @Test
    void withdrawingConsentEndsALiveSession() throws Exception {
        TestClient target = connect("target");
        TestClient viewer = connect("viewer");
        target.sendConsent(true);
        settle();
        viewer.requestSession(target.uuid);
        settle();
        assertTrue(viewer.sawType(Wire.TYPE_SESSION_READY));

        // Revoke by turning spectating off.
        target.sendConsent(false);
        settle();
        assertTrue(viewer.denyReasons.contains(Wire.DENY_REVOKED), "viewer was not told of revocation");
        assertTrue(target.sawType(Wire.TYPE_CAPTURE_STOP), "target was not told to stop capturing");

        int before = viewer.streamFrames.size();
        target.sendGamePacket(new byte[] {7});
        settle();
        assertEquals(before, viewer.streamFrames.size(), "stream continued after revocation");
    }

    @Test
    void cannotSpectateYourself() throws Exception {
        TestClient viewer = connect("viewer");
        viewer.sendConsent(true);
        settle();
        viewer.requestSession(viewer.uuid);
        settle();
        assertFalse(viewer.sawType(Wire.TYPE_SESSION_READY), "self-spectate was allowed");
    }

    @Test
    void aTargetOnAnotherVersionIsRefused() throws Exception {
        // A viewer decodes the target's packets with its own version's protocol codec, so pairing
        // across versions produces a corrupted world rather than a clean failure. The service is
        // the only party that sees both clients before anything is streamed.
        TestClient target = connect("target", "1.21.11");
        TestClient viewer = connect("viewer", "26.1.2");
        target.sendConsent(true);
        settle();
        viewer.requestSession(target.uuid);
        settle();
        assertTrue(viewer.denyReasons.contains(Wire.DENY_VERSION), "no version denial");
        assertFalse(viewer.sawType(Wire.TYPE_SESSION_READY), "cross-version session started");
    }

    @Test
    void matchingVersionsArePaired() throws Exception {
        // The companion to the test above: the version gate must not refuse a legitimate pair.
        TestClient target = connect("target", "26.3");
        TestClient viewer = connect("viewer", "26.3");
        target.sendConsent(true);
        settle();
        viewer.requestSession(target.uuid);
        settle();
        assertTrue(viewer.sawType(Wire.TYPE_SESSION_READY), "matching versions were refused");
    }

    /**
     * A consent update that says what the service already knows must change nothing.
     *
     * <p>Clients re-send consent on a timer rather than only when it changes, so this is the
     * common case, not an edge one. The withhold path originally rebroadcast unconditionally,
     * which meant one client sitting with spectating turned off pushed the whole roster to every
     * connected client every few seconds indefinitely.
     */
    @Test
    void arepeatedConsentUpdateDoesNotRebroadcast() throws Exception {
        TestClient target = connect("target");
        TestClient viewer = connect("viewer");
        settle();

        target.sendConsent(true);
        settle();
        int after = viewer.rosters.get();
        assertTrue(after > 0, "the first consent change sent no roster");
        assertEquals(1, viewer.lastRosterSize, "target missing from the roster");

        // Three more updates saying exactly what was already reported.
        target.sendConsent(true);
        target.sendConsent(true);
        target.sendConsent(true);
        settle();
        assertEquals(after, viewer.rosters.get(), "an unchanged consent update rebroadcast");

        // And the same for a client that stays off.
        viewer.sendConsent(false);
        viewer.sendConsent(false);
        settle();
        assertEquals(after, viewer.rosters.get(), "an unchanged withhold rebroadcast");

        // A real change still does broadcast.
        target.sendConsent(false);
        settle();
        assertTrue(viewer.rosters.get() > after, "a real consent change sent no roster");
        assertEquals(0, viewer.lastRosterSize, "withdrawn target still listed");
    }

    @Test
    void anOfflineTargetIsReportedUnavailable() throws Exception {
        TestClient viewer = connect("viewer");
        viewer.requestSession(uuidOf("never-connected"));
        settle();
        assertTrue(viewer.denyReasons.contains(Wire.DENY_OFFLINE), "no unavailable denial");
    }

    @Test
    void aTargetDisconnectEndsTheSession() throws Exception {
        TestClient target = connect("target");
        TestClient viewer = connect("viewer");
        target.sendConsent(true);
        settle();
        viewer.requestSession(target.uuid);
        settle();
        assertTrue(viewer.sawType(Wire.TYPE_SESSION_READY));

        target.closeBlocking();
        settle();
        assertTrue(viewer.sawType(Wire.TYPE_STREAM_END), "viewer was not told the stream ended");
    }

    /**
     * Two viewers may watch one target at once, and each is told separately.
     *
     * <p>This used to assert the opposite -- that a second viewer the target had not named was
     * refused. Consent is no longer per-player, so a second viewer is legitimate, and what
     * matters instead is that the target is asked to capture once per viewer: the capture-start
     * carries the viewer's id, and the target replays a handshake for each, so a viewer joining
     * an already-running stream still gets one.
     */
    @Test
    void aSecondViewerIsAttachedAndAlsoReceivesTheStream() throws Exception {
        TestClient target = connect("target");
        TestClient first = connect("first");
        TestClient second = connect("second");
        target.sendConsent(true);
        settle();
        first.requestSession(target.uuid);
        settle();
        second.requestSession(target.uuid);
        settle();
        assertTrue(first.sawType(Wire.TYPE_SESSION_READY), "first viewer was refused");
        assertTrue(second.sawType(Wire.TYPE_SESSION_READY), "second viewer was refused");

        target.sendGamePacket(new byte[] {4});
        settle();
        assertFalse(first.streamFrames.isEmpty(), "first viewer received nothing");
        assertFalse(second.streamFrames.isEmpty(), "second viewer received nothing");
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length, "payload length");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "payload byte " + i);
        }
    }
}
