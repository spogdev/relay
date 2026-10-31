package dev.spog.teamlocator.test;

import dev.spog.teamlocator.client.ClientState;
import dev.spog.teamlocator.client.PingHandler;
import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.TrackedPos;
import dev.spog.teamlocator.client.config.TeamConfig;
import dev.spog.teamlocator.client.path.PathTracker;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.util.math.MathHelper;

import java.util.List;

/**
 * {@code /relaytestplayer}, typed as a player would: the test player appears where you stood, shows
 * on the HUD under its name, outlasts the few seconds a quiet teammate's position does, gets a route
 * once you walk away, and is gone after leaving the world.
 */
public class TestPlayerGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        int[] at;
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            context.waitTicks(20);
            context.runOnClient(client -> {
                TeamConfig config = TeamLocatorClient.CONFIG;
                config.hudEnabled = true;
                config.pathFindingMode = TeamConfig.PathFindingMode.ALWAYS;
            });
            at = context.computeOnClient(client -> new int[] {
                    MathHelper.floor(client.player.getX()),
                    MathHelper.floor(client.player.getY() + 0.01),
                    MathHelper.floor(client.player.getZ())});

            context.runOnClient(client -> client.getNetworkHandler().sendChatCommand("relaytestplayer"));
            context.waitTicks(5);
            context.runOnClient(client -> {
                List<TrackedPos> teammates = ClientState.latest();
                check(teammates.size() == 1, "one test player, got " + teammates.size());
                TrackedPos p = teammates.get(0);
                check("TestPlayer1".equals(PingHandler.displayName(client, p.id())),
                        "named TestPlayer1, got " + PingHandler.displayName(client, p.id()));
                check(MathHelper.floor(p.x()) == at[0] && MathHelper.floor(p.z()) == at[2],
                        "where the player stood, got " + p.x() + ", " + p.z());
            });

            // Walk away, past the point a teammate who stopped reporting would have expired.
            world.getServer().runCommand("tp @a " + (at[0] + 15) + " " + at[1] + " " + at[2] + " 90 20");
            context.waitTicks(240);
            context.runOnClient(client -> {
                check(ClientState.latest().size() == 1, "still there after twelve seconds");
                check(PathTracker.route(ClientState.latest().get(0).id()) != null,
                        "a route to the test player");
            });
            context.takeScreenshot("test-player");

            // Looking off to the side, so the marker idles see-through, and drawn large: the skin
            // should show plainly, not tinted the ping colour by the frame behind it.
            int size = context.computeOnClient(client -> TeamLocatorClient.CONFIG.playerMarkerSize);
            context.runOnClient(client -> TeamLocatorClient.CONFIG.playerMarkerSize = 32);
            world.getServer().runCommand("tp @a " + (at[0] + 15) + " " + at[1] + " " + at[2] + " 75 20");
            context.waitTicks(10);
            context.takeScreenshot("test-player-marker-idle");
            context.runOnClient(client -> TeamLocatorClient.CONFIG.playerMarkerSize = size);
            world.getServer().runCommand("tp @a " + (at[0] + 15) + " " + at[1] + " " + at[2] + " 90 20");

            // A raised strip across the way: the route steps up onto it and down off it, and the
            // line has to stay on top of the blocks rather than slope through their edges.
            world.getServer().runCommand("fill " + (at[0] + 5) + " " + at[1] + " " + (at[2] - 20) + " "
                    + (at[0] + 8) + " " + at[1] + " " + (at[2] + 20) + " minecraft:stone");
            world.getServer().runCommand("tp @a " + (at[0] + 14) + " " + at[1] + " " + at[2] + " 90 30");
            context.waitTicks(120);
            context.runOnClient(client -> check(PathTracker.route(ClientState.latest().get(0).id()) != null,
                    "a route over the step"));
            context.takeScreenshot("test-player-step");

            // A second one, then clear both.
            context.runOnClient(client -> client.getNetworkHandler().sendChatCommand("relaytestplayer"));
            context.waitTicks(5);
            context.runOnClient(client -> check(ClientState.latest().size() == 2,
                    "two test players, got " + ClientState.latest().size()));
            context.runOnClient(client -> client.getNetworkHandler().sendChatCommand("relaytestplayer clear"));
            context.waitTicks(5);
            context.runOnClient(client -> check(ClientState.latest().isEmpty(),
                    "none after clear, got " + ClientState.latest().size()));

            // One more, left in place for leaving the world to remove.
            context.runOnClient(client -> client.getNetworkHandler().sendChatCommand("relaytestplayer"));
            context.waitTicks(5);
        }
        context.waitTicks(5);
        context.runOnClient(client -> {
            check(ClientState.latest().isEmpty(), "none after leaving, got " + ClientState.latest().size());
            TeamLocatorClient.CONFIG.pathFindingMode = TeamConfig.PathFindingMode.UNDERGROUND;
        });
    }

    private static void check(boolean ok, String what) {
        System.out.println("TestPlayerGameTest " + (ok ? "ok: " : "FAILED: ") + what);
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
