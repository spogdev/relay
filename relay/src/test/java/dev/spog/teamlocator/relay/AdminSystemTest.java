package dev.spog.teamlocator.relay;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.spog.teamlocator.relay.admin.AdminStore;
import dev.spog.teamlocator.relay.admin.BlacklistStore;
import dev.spog.teamlocator.relay.admin.NameResolver;
import dev.spog.teamlocator.relay.auth.Verifier;
import dev.spog.teamlocator.relay.protocol.Messages;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The moderation system's security properties, driven over real WebSockets against the real
 * {@link RelayServer}: only administrators can run commands, administrators cannot be blacklisted,
 * a blacklisted player is refused and disconnected, and a ban survives a restart.
 *
 * <p>These matter more than the routing tests: a hole here is a privilege escalation, not a
 * cosmetic bug.
 */
class AdminSystemTest {
    private static final Gson GSON = new Gson();

    /** Deterministic name -> UUID, standing in for Mojang's verification. */
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

    /** Resolves any name the same way the verifier does, so offline targets are findable. */
    private static final class StubNames implements NameResolver {
        volatile boolean unavailable;

        @Override
        public UUID resolve(String name) throws Unavailable {
            if (unavailable) {
                throw new Unavailable(new RuntimeException("simulated outage"));
            }
            return name.startsWith("nonexistent") ? null : uuidOf(name);
        }
    }

    @TempDir
    Path dataDir;

    private RelayServer server;
    private int port;
    private Path blacklistFile;
    private StubNames names;
    private final List<TestClient> clients = new ArrayList<>();

    /** Boot a relay whose admin list contains exactly the given names. */
    private void startWith(String... adminNames) throws Exception {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < adminNames.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"uuid\":\"").append(uuidOf(adminNames[i]))
                .append("\",\"name\":\"").append(adminNames[i]).append("\"}");
        }
        json.append(']');
        Path adminFile = dataDir.resolve("admins.json");
        Files.writeString(adminFile, json.toString(), StandardCharsets.UTF_8);
        blacklistFile = dataDir.resolve("blacklist.json");

        names = new StubNames();
        AdminService admins = AdminService.of(
                AdminStore.load(adminFile), BlacklistStore.load(blacklistFile),
                dev.spog.teamlocator.relay.admin.StatsStore.load(dataDir.resolve("stats.json")),
                dev.spog.teamlocator.relay.admin.PingColorStore.load(
                        dataDir.resolve("pingcolors.json")),
                names);
        server = new RelayServer(new InetSocketAddress("127.0.0.1", 0), new StubVerifier(), admins);
        server.start();
        for (int i = 0; i < 100 && server.getPort() <= 0; i++) {
            Thread.sleep(20);
        }
        port = server.getPort();
        assertTrue(port > 0, "relay did not bind");
    }

    @AfterEach
    void stop() throws Exception {
        for (TestClient c : clients) {
            c.closeBlocking();
        }
        clients.clear();
        if (server != null) {
            server.stop(500);
        }
    }

    /** A client that can authenticate, run admin commands, and observe auth failures. */
    private final class TestClient extends WebSocketClient {
        final String name;
        final UUID uuid;
        /** The protocol version this client claims; lets a test stand in for an older mod build. */
        volatile int claimVersion = Messages.PROTOCOL_VERSION;
        private final CountDownLatch authed = new CountDownLatch(1);
        private final CountDownLatch failed = new CountDownLatch(1);
        volatile String failReason;
        /** The admin hint the relay sent on auth-ok, which drives client-side command visibility. */
        volatile boolean sawAdminFlag;
        private final List<String> adminLines = new ArrayList<>();
        private volatile boolean adminError;
        private final Map<String, CountDownLatch> waiters = new ConcurrentHashMap<>();

        TestClient(String name) throws Exception {
            super(new URI("ws://127.0.0.1:" + port));
            this.name = name;
            this.uuid = uuidOf(name);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            JsonObject hello = new JsonObject();
            hello.addProperty("type", "hello");
            hello.addProperty("protocolVersion", claimVersion);
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
                case "auth-ok" -> {
                    sawAdminFlag = obj.has("admin") && obj.get("admin").getAsBoolean();
                    authed.countDown();
                }
                case "auth-fail" -> {
                    failReason = obj.has("reason") ? obj.get("reason").getAsString() : "?";
                    failed.countDown();
                    release("fail");
                }
                case "admin-result" -> {
                    synchronized (adminLines) {
                        adminLines.clear();
                        for (var el : obj.getAsJsonArray("lines")) {
                            adminLines.add(el.getAsString());
                        }
                        adminError = obj.has("error") && obj.get("error").getAsBoolean();
                    }
                    release("admin");
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

        boolean awaitAuth() throws InterruptedException {
            return authed.await(5, TimeUnit.SECONDS);
        }

        boolean awaitFail() throws InterruptedException {
            return failed.await(5, TimeUnit.SECONDS);
        }

        /** Send an admin command and wait for the reply; returns the lines. */
        List<String> admin(String command, String... targets) throws InterruptedException {
            CountDownLatch got = expect("admin");
            JsonObject o = new JsonObject();
            o.addProperty("type", "admin-command");
            o.addProperty("command", command);
            var arr = new com.google.gson.JsonArray();
            for (String t : targets) {
                arr.add(t);
            }
            o.add("args", arr);
            send(GSON.toJson(o));
            assertTrue(got.await(5, TimeUnit.SECONDS), "no admin-result for " + command);
            synchronized (adminLines) {
                return List.copyOf(adminLines);
            }
        }

        boolean lastWasError() {
            synchronized (adminLines) {
                return adminError;
            }
        }
    }

    private TestClient connect(String name) throws Exception {
        return connect(name, Messages.PROTOCOL_VERSION);
    }

    /** Connect while claiming a specific protocol version, to stand in for an older mod build. */
    private TestClient connect(String name, int version) throws Exception {
        TestClient c = new TestClient(name);
        c.claimVersion = version;
        clients.add(c);
        assertTrue(c.connectBlocking(5, TimeUnit.SECONDS), "connect failed for " + name);
        return c;
    }

    @Test
    void aNonAdminCannotRunAnyCommand() throws Exception {
        startWith("boss");
        TestClient rando = connect("rando");
        assertTrue(rando.awaitAuth());

        for (String cmd : List.of("test", "blacklist", "whitelist")) {
            List<String> out = rando.admin(cmd, "victim");
            assertTrue(rando.lastWasError(), cmd + " must be refused for a non-admin");
            assertTrue(out.get(0).toLowerCase().contains("permission"),
                    "refusal should mention permission, got: " + out);
        }
        // And critically, the refused blacklist must not have taken effect.
        assertFalse(Files.exists(blacklistFile) && Files.readString(blacklistFile).contains("victim"),
                "a non-admin's blacklist attempt must not write the file");
    }

    @Test
    void anAdminCannotBlacklistAnotherAdmin() throws Exception {
        startWith("boss", "deputy");
        TestClient boss = connect("boss");
        assertTrue(boss.awaitAuth());

        List<String> out = boss.admin("blacklist", "deputy");
        assertTrue(boss.lastWasError(), "banning an admin must fail");
        assertTrue(out.get(0).contains("administrator"),
                "refusal should say why, got: " + out);

        // The deputy must still be able to connect.
        TestClient deputy = connect("deputy");
        assertTrue(deputy.awaitAuth(), "an admin must never be bannable");
    }

    @Test
    void aBlacklistedPlayerIsRefusedAtHandshakeWithTheBannedReason() throws Exception {
        startWith("boss");
        TestClient boss = connect("boss");
        assertTrue(boss.awaitAuth());
        boss.admin("blacklist", "griefer");
        assertFalse(boss.lastWasError(), "banning a non-admin should succeed");

        TestClient griefer = connect("griefer");
        assertTrue(griefer.awaitFail(), "a blacklisted player must be refused");
        assertEquals(Messages.REASON_BANNED, griefer.failReason,
                "the reason must be the stable 'banned' code the client matches on");
    }

    @Test
    void aLiveSessionIsDisconnectedTheMomentTheBanLands() throws Exception {
        startWith("boss");
        TestClient boss = connect("boss");
        TestClient griefer = connect("griefer");
        assertTrue(boss.awaitAuth());
        assertTrue(griefer.awaitAuth(), "precondition: griefer is connected and authenticated");

        CountDownLatch kicked = griefer.expect("fail");
        boss.admin("blacklist", "griefer");
        assertTrue(kicked.await(5, TimeUnit.SECONDS),
                "an already-connected banned player must be kicked, not left running");
        assertEquals(Messages.REASON_BANNED, griefer.failReason);
    }

    @Test
    void whitelistRestoresAccess() throws Exception {
        startWith("boss");
        TestClient boss = connect("boss");
        assertTrue(boss.awaitAuth());
        boss.admin("blacklist", "griefer");

        TestClient blocked = connect("griefer");
        assertTrue(blocked.awaitFail(), "precondition: the ban is in force");

        boss.admin("whitelist", "griefer");
        assertFalse(boss.lastWasError(), "unbanning should succeed");

        TestClient back = connect("griefer");
        assertTrue(back.awaitAuth(), "a whitelisted player must be able to reconnect");
    }

    @Test
    void aBanIsPersistedAndSurvivesAReload() throws Exception {
        startWith("boss");
        TestClient boss = connect("boss");
        assertTrue(boss.awaitAuth());
        boss.admin("blacklist", "griefer");

        // Re-read the file the way a restarting relay would: the ban must still be there.
        BlacklistStore reloaded = BlacklistStore.load(blacklistFile);
        assertTrue(reloaded.isBanned(uuidOf("griefer")),
                "a ban that vanished on restart would be no ban at all");
        assertFalse(reloaded.isBanned(uuidOf("boss")));
    }

    @Test
    void testReportsAConnectedPlayersVersionAndNotAnAbsentOne() throws Exception {
        startWith("boss");
        TestClient boss = connect("boss");
        TestClient mate = connect("mate");
        assertTrue(boss.awaitAuth());
        assertTrue(mate.awaitAuth());

        List<String> present = boss.admin("test", "mate");
        assertFalse(boss.lastWasError());
        assertTrue(present.get(0).contains("v" + Messages.PROTOCOL_VERSION),
                "test should report the negotiated protocol version, got: " + present);

        List<String> absent = boss.admin("test", "ghost");
        assertTrue(absent.get(0).contains("not connected"),
                "an unconnected player must not be probed or reported as a user, got: " + absent);
    }

    @Test
    void afirstRunSeedsTheDefaultAdminsButNeverOverridesAnExistingFile() throws Exception {
        Path fresh = dataDir.resolve("seeded.json");
        AdminStore seeded = AdminStore.load(fresh);
        assertTrue(Files.exists(fresh), "a missing admin file should be created");
        assertEquals(2, seeded.size(), "a fresh relay should come up with its default admins");
        assertTrue(seeded.isAdmin(UUID.fromString("404c6565-ba27-41bb-8b25-7df3755be61f")));
        assertTrue(seeded.isAdmin(UUID.fromString("3b6fb9c6-4d60-4a17-80a8-966ade749601")));

        // An operator who deliberately empties the file must stay empty on the next boot: silently
        // re-seeding would hand back admin to accounts they just removed.
        Path emptied = dataDir.resolve("emptied.json");
        Files.writeString(emptied, "[]", StandardCharsets.UTF_8);
        assertEquals(0, AdminStore.load(emptied).size(),
                "an explicitly empty admin list must never be re-seeded");
    }

    @Test
    void anOlderClientIsBannedJustTheSameAndCanBeKickedLive() throws Exception {
        // Enforcement must not depend on the target's mod version: the ban is keyed on the
        // Mojang-verified UUID, and a player on an older build must not be able to sidestep it by
        // simply not having updated. Only the admin ISSUING the command needs the new client.
        startWith("boss");
        TestClient boss = connect("boss");
        assertTrue(boss.awaitAuth());

        int legacy = Messages.MIN_SUPPORTED_VERSION;
        TestClient old = connect("oldbuild", legacy);
        assertTrue(old.awaitAuth(), "precondition: an older client connects normally");

        CountDownLatch kicked = old.expect("fail");
        boss.admin("blacklist", "oldbuild");
        assertFalse(boss.lastWasError(), "banning an older-version player must succeed");
        assertTrue(kicked.await(5, TimeUnit.SECONDS),
                "a live older-version session must be kicked like any other");
        assertEquals(Messages.REASON_BANNED, old.failReason,
                "the same reason code is sent regardless of version; old clients just render it "
                        + "as their generic disconnect");

        // And it must stay barred on reconnect, still on the old protocol.
        TestClient again = connect("oldbuild", legacy);
        assertTrue(again.awaitFail(), "an older client must stay banned across reconnects");
        assertEquals(Messages.REASON_BANNED, again.failReason);
    }

    @Test
    void statsCountsLivePlayersAndUniqueVisitorsAcrossReconnects() throws Exception {
        startWith("boss");
        TestClient boss = connect("boss");
        TestClient mate = connect("mate");
        assertTrue(boss.awaitAuth());
        assertTrue(mate.awaitAuth());

        List<String> out = boss.admin("stats");
        assertFalse(boss.lastWasError());
        String joined = String.join("\n", out);
        assertTrue(joined.contains("Connected now: 2"), "expected 2 online, got:\n" + joined);
        assertTrue(joined.contains("Unique players (all time): 2"),
                "expected 2 unique, got:\n" + joined);

        // A reconnect is the same player: the unique count must not drift upward, which is the
        // whole reason the counter stores UUIDs instead of incrementing per connection.
        mate.closeBlocking();
        TestClient mateAgain = connect("mate");
        assertTrue(mateAgain.awaitAuth());
        String after = String.join("\n", boss.admin("stats"));
        assertTrue(after.contains("Unique players (all time): 2"),
                "a reconnect must not inflate the unique count, got:\n" + after);
    }

    @Test
    void theUniquePlayerCountSurvivesARestart() throws Exception {
        startWith("boss");
        TestClient boss = connect("boss");
        TestClient mate = connect("mate");
        assertTrue(boss.awaitAuth());
        assertTrue(mate.awaitAuth());
        boss.admin("stats"); // force at least one round-trip so both logins are recorded

        var reloaded = dev.spog.teamlocator.relay.admin.StatsStore.load(dataDir.resolve("stats.json"));
        assertEquals(2, reloaded.uniqueCount(),
                "the all-time count must be persisted, not held only in memory");
    }

    @Test
    void theAdminHintIsSentToAdminsAndWithheldFromEveryoneElse() throws Exception {
        // Drives whether the client offers /relay at all. It is a hint, not a permission: the
        // refusal tests above prove a non-admin is rejected even if their client ignores this.
        startWith("boss");
        TestClient boss = connect("boss");
        TestClient rando = connect("rando");
        assertTrue(boss.awaitAuth());
        assertTrue(rando.awaitAuth());

        assertTrue(boss.sawAdminFlag, "an admin must be told, so the command appears for them");
        assertFalse(rando.sawAdminFlag, "a non-admin must not be told, so the command stays hidden");
    }

    @Test
    void aMojangOutageIsReportedAsUnknownRatherThanNoSuchPlayer() throws Exception {
        startWith("boss");
        TestClient boss = connect("boss");
        assertTrue(boss.awaitAuth());
        names.unavailable = true;

        List<String> out = boss.admin("blacklist", "someoneOffline");
        assertTrue(boss.lastWasError());
        assertTrue(out.get(0).contains("Could not reach Mojang"),
                "an outage must not be reported as 'no such player', got: " + out);
    }
}
