package dev.spog.teamlocator.test;

import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.config.TeamConfig;
import dev.spog.teamlocator.client.config.TrustEntry;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.UUID;

/**
 * A per-server trust list and active-list choice survive rejoining and restarting, over a real
 * connection to a real server.
 *
 * <p>The harness joins its dedicated server as {@code localhost:<port>}, while the socket resolves
 * to the loopback IP: the same split as a player typing a hostname for a network whose IP changes,
 * which is what lost lists saved under the IP. The test starts from what an earlier version saved
 * -- a list and a Server choice under the IP -- and checks they carry over to the typed address,
 * that the relay is still scoped by the IP, that a choice made in the session is restored on the
 * next join, and that both are on disk for the next launch.
 */
public class TrustListGameTest implements FabricClientGameTest {
    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestDedicatedServerContext server = context.worldBuilder().createServer()) {
            int port = server.computeOnServer(MinecraftServer::getServerPort);
            // What the harness types, normalized the way the config does: the default port is
            // dropped, so a server on 25565 is saved as plain "localhost".
            String typed = port == 25565 ? "localhost" : "localhost:" + port;

            // What an earlier version saved: under the resolved IP. Both loopback spellings, since
            // whether localhost resolves to IPv4 or IPv6 is up to the machine.
            context.runOnClient(client -> {
                TeamConfig config = TeamLocatorClient.CONFIG;
                config.servers.clear();
                config.serverModes.clear();
                for (String ip : List.of("127.0.0.1", "0:0:0:0:0:0:0:1")) {
                    TeamConfig.TrustList list = new TeamConfig.TrustList();
                    list.trusted.add(new TrustEntry(NOTCH, "Notch"));
                    config.servers.put(ip + ":" + port, list);
                    config.serverModes.put(ip + ":" + port, TeamConfig.Mode.SERVER);
                }
                config.activeMode = TeamConfig.Mode.GLOBAL;
            });

            try (TestServerConnection connection = server.connect()) {
                context.waitTicks(5);
                context.runOnClient(client -> {
                    TeamConfig config = TeamLocatorClient.CONFIG;
                    check(typed.equals(TeamConfig.currentServerKey()),
                            "saved under the typed address, got " + TeamConfig.currentServerKey());
                    String scope = TeamConfig.currentRelayScope();
                    check(scope != null && !scope.equals(typed) && scope.endsWith(":" + port),
                            "relay still scoped by the resolved IP, got " + scope);
                    check(config.activeMode == TeamConfig.Mode.SERVER,
                            "the old Server choice carried over, got " + config.activeMode);
                    check(hasNotch(config.activeList()), "the old list carried over");

                    // As the Active List dropdown does.
                    config.chooseActiveMode(TeamConfig.Mode.GLOBAL);
                });
            }

            try (TestServerConnection connection = server.connect()) {
                context.waitTicks(5);
                context.runOnClient(client -> {
                    TeamConfig config = TeamLocatorClient.CONFIG;
                    check(config.activeMode == TeamConfig.Mode.GLOBAL,
                            "the choice made last session is restored on rejoin, got "
                                    + config.activeMode);
                    config.chooseActiveMode(TeamConfig.Mode.SERVER);
                    check(hasNotch(config.activeList()), "the list is still there on rejoin");
                });
            }

            // Let the client finish leaving first: the mod resets the menu to Global and saves on
            // disconnect, queued on the client thread, and reading the file before that runs would
            // see the session's choice rather than what a later launch starts from.
            context.waitTicks(5);

            // A restart, as far as the config is concerned: a fresh copy read back from disk.
            context.runOnClient(client -> {
                TeamConfig fresh = TeamConfig.load();
                check(hasNotch(fresh.servers.get(typed)), "the list is saved under " + typed);
                check(fresh.serverModes.get(typed) == TeamConfig.Mode.SERVER,
                        "the choice is saved under " + typed + ", got " + fresh.serverModes.get(typed));
            });

            // The launch after that: the live config replaced with what was read back from disk, as
            // at startup. The menu is always Global, so this is the case where a lost choice shows:
            // the join has to bring This Server back, not leave the menu's Global in place.
            context.runOnClient(client -> {
                TeamConfig fresh = TeamConfig.load();
                TeamConfig config = TeamLocatorClient.CONFIG;
                config.servers = fresh.servers;
                config.serverModes = fresh.serverModes;
                config.activeMode = fresh.activeMode;
                check(config.activeMode == TeamConfig.Mode.GLOBAL,
                        "the menu is Global after a restart, got " + config.activeMode);
            });

            try (TestServerConnection connection = server.connect()) {
                context.waitTicks(5);
                context.runOnClient(client -> {
                    TeamConfig config = TeamLocatorClient.CONFIG;
                    check(config.activeMode == TeamConfig.Mode.SERVER,
                            "This Server is still chosen after a restart, got " + config.activeMode);
                    check(hasNotch(config.activeList()), "the list is there after a restart");
                });
            }

            context.runOnClient(client -> {
                TeamConfig config = TeamLocatorClient.CONFIG;
                config.servers.clear();
                config.serverModes.clear();
                config.activeMode = TeamConfig.Mode.GLOBAL;
                config.save();
            });
        }
    }

    private static boolean hasNotch(TeamConfig.TrustList list) {
        return list != null && list.trusted.stream().anyMatch(e -> NOTCH.toString().equals(e.uuid));
    }

    private static void check(boolean ok, String what) {
        // Printed as well as thrown, so a failure is easy to find in the client log.
        System.out.println("TrustListGameTest " + (ok ? "ok: " : "FAILED: ") + what);
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
