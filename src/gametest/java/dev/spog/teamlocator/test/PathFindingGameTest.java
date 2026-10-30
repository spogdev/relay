package dev.spog.teamlocator.test;

import dev.spog.teamlocator.client.ClientState;
import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.TrackedPos;
import dev.spog.teamlocator.client.config.TeamConfig;
import dev.spog.teamlocator.client.path.PathFinder;
import dev.spog.teamlocator.client.path.PathTracker;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.util.math.MathHelper;

import java.util.List;
import java.util.UUID;

/**
 * Routes worked out in a real world, from the client's own copy of the blocks.
 *
 * <p>Builds a wall the player cannot get past with a one-block hole in it, puts a teammate on the far
 * side, and checks the route that is kept ready for them goes through the hole with a pearl. Then
 * opens a doorway beside the hole and checks the route switches to walking through it. The unit tests
 * cover the search itself on made-up worlds; this covers reading real blocks, the tick that keeps
 * routes ready, and drawing them -- the screenshots are what to look at for that.
 */
public class PathFindingGameTest implements FabricClientGameTest {
    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            context.waitTicks(20);
            int[] at = context.computeOnClient(client -> new int[] {
                    MathHelper.floor(client.player.getX()),
                    MathHelper.floor(client.player.getY() + 0.01),
                    MathHelper.floor(client.player.getZ())});
            int x = at[0];
            int y = at[1];
            int z = at[2];

            // Facing the wall from five blocks back, so both routes are in the screenshots.
            world.getServer().runCommand("tp @a " + x + " " + y + " " + z + " -90 25");
            world.getServer().runCommand("fill " + (x + 5) + " " + y + " " + (z - 12) + " "
                    + (x + 5) + " " + (y + 3) + " " + (z + 12) + " minecraft:stone");
            world.getServer().runCommand("setblock " + (x + 5) + " " + y + " " + z + " minecraft:air");
            context.runOnClient(client -> {
                TeamConfig config = TeamLocatorClient.CONFIG;
                config.pathFindingMode = TeamConfig.PathFindingMode.ALWAYS;
            });
            context.waitTicks(10);

            report(context, x + 10, y, z);
            List<PathFinder.Point> pearled = context.computeOnClient(client -> PathTracker.route(NOTCH));
            check(pearled != null, "a route was found through the hole");
            check(pearled.stream().anyMatch(PathFinder.Point::pearl),
                    "the route pearls through the hole, got " + pearled);
            context.takeScreenshot("path-pearl-hole");
            // From the side and above, where the arc's shape and the rings at its ends show.
            world.getServer().runCommand("tp @a " + (x + 2) + " " + y + " " + (z - 3)
                    + " -55 30");
            report(context, x + 10, y, z);
            context.takeScreenshot("path-pearl-side");
            world.getServer().runCommand("tp @a " + x + " " + y + " " + z + " -90 25");

            // A doorway beside the hole: walking through it is quicker than throwing a pearl.
            world.getServer().runCommand("fill " + (x + 5) + " " + y + " " + (z + 1) + " "
                    + (x + 5) + " " + (y + 1) + " " + (z + 1) + " minecraft:air");
            context.waitTicks(10);
            // A route is redone at least every couple of seconds, so blocks changing are picked up.
            for (int i = 0; i < 3; i++) {
                report(context, x + 10, y, z);
            }
            List<PathFinder.Point> walked = context.computeOnClient(client -> PathTracker.route(NOTCH));
            check(walked != null, "a route was found through the doorway");
            check(walked.stream().noneMatch(PathFinder.Point::pearl),
                    "the route walks through the doorway, got " + walked);
            context.takeScreenshot("path-doorway");

            // Keybind Only: the route is still kept ready, but nothing is drawn without the key.
            context.runOnClient(client ->
                    TeamLocatorClient.CONFIG.pathFindingMode = TeamConfig.PathFindingMode.KEYBIND);
            report(context, x + 10, y, z);
            check(context.computeOnClient(client -> PathTracker.route(NOTCH)) != null,
                    "a route is kept ready in Keybind Only");
            context.takeScreenshot("path-keybind-only");

            context.runOnClient(client -> {
                TeamLocatorClient.CONFIG.pathFindingMode = TeamConfig.PathFindingMode.UNDERGROUND;
                ClientState.reset();
            });
        }
    }

    /** Reports the teammate where the relay would, and gives the tracker time to route to them. */
    private static void report(ClientGameTestContext context, int x, int y, int z) {
        for (int i = 0; i < 4; i++) {
            context.runOnClient(client -> ClientState.updatePosition(new TrackedPos(NOTCH,
                    x + 0.5, y, z + 0.5, client.world.getRegistryKey().getValue())));
            context.waitTicks(15);
        }
    }

    private static void check(boolean ok, String what) {
        System.out.println("PathFindingGameTest " + (ok ? "ok: " : "FAILED: ") + what);
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
