package dev.spog.teamlocator.relay;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.spog.teamlocator.relay.auth.Verifier;
import dev.spog.teamlocator.relay.protocol.Messages;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relay enforces the dashboard's access rules over real WebSockets: a denied player is refused
 * at auth, a player denied while connected is disconnected when the rules change, and a player
 * the rules allow gets through. The dashboard itself is stubbed; the relay only ever asks it
 * {@link Dashboard#allows}.
 */
class DashboardEnforcementTest {
    private static final Gson GSON = new Gson();

    private static UUID uuidOf(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

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

    /** A dashboard whose deny set the test edits directly. */
    private static final class StubDashboard implements Dashboard {
        final Set<UUID> denied = ConcurrentHashMap.newKeySet();

        @Override
        public boolean allows(UUID player) {
            return !denied.contains(player);
        }

        @Override
        public String pingColor(UUID player) {
            return null;
        }
    }

    private RelayServer server;
    private final StubDashboard dashboard = new StubDashboard();
    private final List<TestClient> clients = new ArrayList<>();

    private void start() throws Exception {
        server = new RelayServer(new InetSocketAddress("127.0.0.1", 0), new StubVerifier());
        server.useDashboard(dashboard);
        server.start();
        for (int i = 0; i < 100 && server.getPort() <= 0; i++) {
            Thread.sleep(20);
        }
        assertTrue(server.getPort() > 0, "relay did not bind");
    }

    @AfterEach
    void stop() throws Exception {
        for (TestClient c : clients) {
            c.closeBlocking();
        }
        if (server != null) {
            server.stop(500);
        }
    }

    private TestClient connect(String name) throws Exception {
        TestClient c = new TestClient(name, server.getPort());
        clients.add(c);
        assertTrue(c.connectBlocking(5, TimeUnit.SECONDS));
        return c;
    }

    @Test
    void aDeniedPlayerIsRefusedAtAuth() throws Exception {
        start();
        dashboard.denied.add(uuidOf("griefer"));
        TestClient c = connect("griefer");
        assertTrue(c.failed.await(5, TimeUnit.SECONDS), "denied player should get auth-fail");
        assertEquals(Messages.REASON_BANNED, c.failReason);
        assertFalse(c.authed.getCount() == 0, "denied player must never be authenticated");
    }

    @Test
    void anAllowedPlayerGetsIn() throws Exception {
        start();
        dashboard.denied.add(uuidOf("someone-else"));
        TestClient c = connect("friend");
        assertTrue(c.authed.await(5, TimeUnit.SECONDS));
        assertEquals(1, server.connectedPlayers().size());
        assertEquals(uuidOf("friend"), server.connectedPlayers().get(0).uuid());
    }

    @Test
    void aPlayerDeniedWhileConnectedIsDisconnectedWhenRulesChange() throws Exception {
        start();
        TestClient c = connect("soon-banned");
        assertTrue(c.authed.await(5, TimeUnit.SECONDS));
        dashboard.denied.add(c.uuid);
        server.kickDenied();
        assertTrue(c.failed.await(5, TimeUnit.SECONDS), "should be told why before the close");
        assertEquals(Messages.REASON_BANNED, c.failReason);
        assertTrue(c.closed.await(5, TimeUnit.SECONDS), "socket should be closed");
    }

    private static final class TestClient extends WebSocketClient {
        final String name;
        final UUID uuid;
        final CountDownLatch authed = new CountDownLatch(1);
        final CountDownLatch failed = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        volatile String failReason;

        TestClient(String name, int port) throws Exception {
            super(new URI("ws://127.0.0.1:" + port));
            this.name = name;
            this.uuid = uuidOf(name);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            JsonObject hello = new JsonObject();
            hello.addProperty("type", "hello");
            hello.addProperty("protocolVersion", Messages.PROTOCOL_VERSION);
            hello.addProperty("mcServer", "play.example.net");
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
                default -> { }
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            closed.countDown();
        }

        @Override
        public void onError(Exception ex) {
        }
    }
}
