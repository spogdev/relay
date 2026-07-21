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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The availability flow behind {@code /available}: the relay may only list a peer after that peer's
 * own client verified it would display the alert. These drive the real relay over real sockets and
 * pin the promises that matter: a "no" or a silent peer is never listed, a pre-probe client is
 * never probed (it cannot answer), and the requester always gets an answer — even when nobody
 * responds — rather than hanging forever.
 */
class AvailabilityQueryTest {
    private static final Gson GSON = new Gson();

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
    private final List<ProbeClient> clients = new ArrayList<>();

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
        for (ProbeClient c : clients) {
            c.closeBlocking();
        }
        clients.clear();
        server.stop(500);
    }

    /** How a simulated client reacts to an availability probe. */
    private enum ProbeReply { YES, NO, IGNORE }

    private final class ProbeClient extends WebSocketClient {
        final String name;
        final UUID uuid;
        private final int claimedVersion;
        private final ProbeReply probeReply;
        private final CountDownLatch authed = new CountDownLatch(1);
        final List<JsonObject> probesReceived = new ArrayList<>();
        final List<List<String>> results = new ArrayList<>();
        private volatile CountDownLatch resultWaiter;

        ProbeClient(String name, int claimedVersion, ProbeReply probeReply) throws Exception {
            super(new URI("ws://127.0.0.1:" + port));
            this.name = name;
            this.uuid = uuidOf(name);
            this.claimedVersion = claimedVersion;
            this.probeReply = probeReply;
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            JsonObject hello = new JsonObject();
            hello.addProperty("type", "hello");
            hello.addProperty("protocolVersion", claimedVersion);
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
                case "availability-probe" -> {
                    synchronized (probesReceived) {
                        probesReceived.add(obj);
                    }
                    if (probeReply != ProbeReply.IGNORE) {
                        JsonObject r = new JsonObject();
                        r.addProperty("type", "availability-response");
                        r.addProperty("probeId", obj.get("probeId").getAsString());
                        r.addProperty("available", probeReply == ProbeReply.YES);
                        send(GSON.toJson(r));
                    }
                }
                case "availability-result" -> {
                    List<String> receivers = new ArrayList<>();
                    obj.getAsJsonArray("receivers").forEach(e -> receivers.add(e.getAsString()));
                    synchronized (results) {
                        results.add(receivers);
                    }
                    CountDownLatch w = resultWaiter;
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

        void awaitAuth() throws InterruptedException {
            assertTrue(authed.await(5, TimeUnit.SECONDS), name + " failed to authenticate");
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

        /** Send /available's query and return a latch that opens when the result lands. */
        CountDownLatch query() {
            CountDownLatch latch = new CountDownLatch(1);
            resultWaiter = latch;
            JsonObject o = new JsonObject();
            o.addProperty("type", "availability-query");
            send(GSON.toJson(o));
            return latch;
        }

        List<String> lastResult() {
            synchronized (results) {
                return results.get(results.size() - 1);
            }
        }
    }

    private ProbeClient connect(String name, int version, ProbeReply reply) throws Exception {
        ProbeClient c = new ProbeClient(name, version, reply);
        clients.add(c);
        assertTrue(c.connectBlocking(5, TimeUnit.SECONDS), "connect failed for " + name);
        c.awaitAuth();
        return c;
    }

    private static void settle() throws InterruptedException {
        Thread.sleep(300);
    }

    @Test
    void aPeerThatConfirmsIsListedAndTheProbeCarriesTheSendersIdentity() throws Exception {
        ProbeClient alice = connect("alice", Messages.PROTOCOL_VERSION, ProbeReply.YES);
        ProbeClient bob = connect("bob", Messages.PROTOCOL_VERSION, ProbeReply.YES);
        alice.sendTrust("bob");
        bob.sendTrust("alice");
        settle();

        CountDownLatch done = alice.query();
        assertTrue(done.await(5, TimeUnit.SECONDS), "the aggregate result must arrive");
        assertEquals(List.of(bob.uuid.toString()), alice.lastResult(),
                "a confirming mutually-trusted peer must be listed");

        synchronized (bob.probesReceived) {
            assertEquals(1, bob.probesReceived.size(), "Bob must be probed exactly once");
            JsonObject probe = bob.probesReceived.get(0);
            assertEquals(alice.uuid.toString(), probe.get("attacker").getAsString(),
                    "the probe must name the would-be sender so the client can check its mute list");
            assertEquals("play.example.net", probe.get("mcServer").getAsString(),
                    "the probe must carry the sender's scope for the known-server check");
        }
    }

    @Test
    void aPeerThatDeclinesIsNotListed() throws Exception {
        ProbeClient alice = connect("alice", Messages.PROTOCOL_VERSION, ProbeReply.YES);
        ProbeClient bob = connect("bob", Messages.PROTOCOL_VERSION, ProbeReply.NO);
        alice.sendTrust("bob");
        bob.sendTrust("alice");
        settle();

        CountDownLatch done = alice.query();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(alice.lastResult().isEmpty(),
                "a peer whose client says it would NOT display the alert must not be listed");
    }

    @Test
    void aPeerWithoutMutualTrustIsNeverProbed() throws Exception {
        ProbeClient alice = connect("alice", Messages.PROTOCOL_VERSION, ProbeReply.YES);
        ProbeClient mallory = connect("mallory", Messages.PROTOCOL_VERSION, ProbeReply.YES);
        alice.sendTrust("mallory"); // one-way: mallory does not trust alice back
        settle();

        CountDownLatch done = alice.query();
        assertTrue(done.await(5, TimeUnit.SECONDS),
                "no candidates means an immediate empty result, not silence");
        assertTrue(alice.lastResult().isEmpty());
        synchronized (mallory.probesReceived) {
            assertTrue(mallory.probesReceived.isEmpty(),
                    "a non-mutual peer must never even be asked");
        }
    }

    @Test
    void anOldClientIsNeitherProbedNorListed() throws Exception {
        // A v2 client would display a real alert, but it cannot answer a probe, and /available
        // promises only verified receivers — so it is excluded rather than guessed at.
        ProbeClient alice = connect("alice", Messages.PROTOCOL_VERSION, ProbeReply.YES);
        ProbeClient oldBob = connect("bob", 2, ProbeReply.YES);
        alice.sendTrust("bob");
        oldBob.sendTrust("alice");
        settle();

        CountDownLatch done = alice.query();
        assertTrue(done.await(5, TimeUnit.SECONDS),
                "with only unprobeable candidates the result must still arrive immediately");
        assertTrue(alice.lastResult().isEmpty(), "an unverifiable peer must not be promised");
        synchronized (oldBob.probesReceived) {
            assertTrue(oldBob.probesReceived.isEmpty(),
                    "a pre-v3 client must never be sent a probe it cannot understand");
        }
    }

    @Test
    void aSilentPeerIsDroppedWhenTheProbeWindowCloses() throws Exception {
        ProbeClient alice = connect("alice", Messages.PROTOCOL_VERSION, ProbeReply.YES);
        ProbeClient bob = connect("bob", Messages.PROTOCOL_VERSION, ProbeReply.IGNORE);
        alice.sendTrust("bob");
        bob.sendTrust("alice");
        settle();

        CountDownLatch done = alice.query();
        assertTrue(done.await(5, TimeUnit.SECONDS),
                "the timeout must close the probe window and send what it has");
        assertTrue(alice.lastResult().isEmpty(),
                "a peer that never answered must not be listed — it was not verified");
    }

    @Test
    void aMixedGroupListsExactlyTheConfirmedPeers() throws Exception {
        ProbeClient alice = connect("alice", Messages.PROTOCOL_VERSION, ProbeReply.YES);
        ProbeClient yes = connect("yes", Messages.PROTOCOL_VERSION, ProbeReply.YES);
        ProbeClient no = connect("no", Messages.PROTOCOL_VERSION, ProbeReply.NO);
        ProbeClient silent = connect("silent", Messages.PROTOCOL_VERSION, ProbeReply.IGNORE);
        alice.sendTrust("yes", "no", "silent");
        yes.sendTrust("alice");
        no.sendTrust("alice");
        silent.sendTrust("alice");
        settle();

        CountDownLatch done = alice.query();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(yes.uuid.toString()), alice.lastResult(),
                "only the peer that confirmed may be listed");
    }
}
