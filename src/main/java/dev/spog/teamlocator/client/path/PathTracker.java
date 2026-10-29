package dev.spog.teamlocator.client.path;

import dev.spog.teamlocator.client.ClientState;
import dev.spog.teamlocator.client.TrackedPos;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps a route to every teammate in render distance worked out ahead of time, so pressing the Show
 * Paths key draws them at once instead of waiting on a search.
 *
 * <p>Runs on the client tick, within a time budget: searches are spread over as many ticks as they
 * need rather than holding up one. A route is redone when either end moves to another block, and now
 * and then regardless, since the blocks along it can change.
 */
@Environment(EnvType.CLIENT)
public final class PathTracker {
    /** Search time allowed per tick, across all teammates. */
    private static final long BUDGET_NANOS = 2_000_000L;
    /** Positions expanded between checks of the time budget. */
    private static final int BATCH = 128;
    /** The most positions one search looks at before deciding there is no route. */
    private static final int MAX_NODES = 60_000;
    /** Minimum gap between searches for one teammate, in ticks, while either end moves. */
    private static final int MOVED_INTERVAL = 5;
    /** Gap after which a route is redone even if nobody moved, in ticks. */
    private static final int REFRESH_INTERVAL = 40;
    /** Gap before trying again after no route was found, in ticks. */
    private static final int FAILED_INTERVAL = 20;
    /** Teammates closer than this have no route drawn: they are right there. */
    private static final double MIN_DISTANCE = 3.0;

    private static final class Route {
        List<PathFinder.Point> points;
        PathFinder search;
        long searchFrom;
        long searchTo;
        long shownFrom;
        long shownTo;
        int sinceSearch = Integer.MAX_VALUE;
        boolean failed;
    }

    private static final Map<UUID, Route> routes = new HashMap<>();
    private static ClientWorld terrainLevel;
    private static LevelTerrain terrain;

    private PathTracker() {
    }

    /** The current route to a teammate, start first, or {@code null} if there is none. */
    public static List<PathFinder.Point> route(UUID teammate) {
        Route r = routes.get(teammate);
        return r == null ? null : r.points;
    }

    public static void reset() {
        routes.clear();
        terrainLevel = null;
        terrain = null;
    }

    /** Whether a position is under a roof: anything solid between it and the sky. */
    public static boolean underground(ClientWorld level, double x, double y, double z) {
        int bx = MathHelper.floor(x);
        int bz = MathHelper.floor(z);
        if (!level.isChunkLoaded(bx >> 4, bz >> 4)) {
            return false;
        }
        return level.getTopY(Heightmap.Type.MOTION_BLOCKING, bx, bz) > MathHelper.floor(y + 1.62) + 1;
    }

    /** Where a teammate is: their player in the world when it is loaded, else what the relay said. */
    public static double[] position(ClientWorld level, TrackedPos pos) {
        PlayerEntity entity = level.getPlayerByUuid(pos.id());
        if (entity != null) {
            return new double[] {entity.getX(), entity.getY(), entity.getZ()};
        }
        return new double[] {pos.x(), pos.y(), pos.z()};
    }

    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity player = mc.player;
        ClientWorld level = mc.world;
        if (player == null || level == null) {
            reset();
            return;
        }
        if (level != terrainLevel) {
            routes.clear();
            terrainLevel = level;
            terrain = new LevelTerrain(level);
        }

        double reach = mc.options.getClampedViewDistance() * 16.0;
        int sx = MathHelper.floor(player.getX());
        int sy = MathHelper.floor(player.getY() + 0.01);
        int sz = MathHelper.floor(player.getZ());
        long from = PathFinder.key(sx, sy, sz);

        Set<UUID> wanted = new HashSet<>();
        for (TrackedPos pos : ClientState.latest()) {
            if (pos.id().equals(player.getUuid())
                    || !pos.dimension().equals(level.getRegistryKey().getValue())) {
                continue;
            }
            double[] at = position(level, pos);
            double dx = at[0] - player.getX();
            double dz = at[2] - player.getZ();
            double dy = at[1] - player.getY();
            if (dx * dx + dz * dz > reach * reach
                    || !level.isChunkLoaded(MathHelper.floor(at[0]) >> 4, MathHelper.floor(at[2]) >> 4)) {
                continue;
            }
            wanted.add(pos.id());
            Route r = routes.computeIfAbsent(pos.id(), id -> new Route());
            if (r.sinceSearch < Integer.MAX_VALUE) {
                r.sinceSearch++;
            }
            if (dx * dx + dy * dy + dz * dz < MIN_DISTANCE * MIN_DISTANCE) {
                r.points = null;
                r.search = null;
                continue;
            }
            int gx = MathHelper.floor(at[0]);
            int gy = MathHelper.floor(at[1] + 0.01);
            int gz = MathHelper.floor(at[2]);
            long to = PathFinder.key(gx, gy, gz);
            if (r.search != null) {
                continue;
            }
            boolean moved = from != r.shownFrom || to != r.shownTo;
            int wait = r.failed ? FAILED_INTERVAL : moved ? MOVED_INTERVAL : REFRESH_INTERVAL;
            if (r.sinceSearch >= wait) {
                r.search = new PathFinder(terrain, sx, sy, sz, gx, gy, gz, MAX_NODES);
                r.searchFrom = from;
                r.searchTo = to;
                r.sinceSearch = 0;
            }
        }
        routes.keySet().retainAll(wanted);

        long deadline = System.nanoTime() + BUDGET_NANOS;
        boolean working = true;
        while (working && System.nanoTime() < deadline) {
            working = false;
            for (Route r : routes.values()) {
                if (r.search == null) {
                    continue;
                }
                PathFinder.State state = r.search.step(BATCH);
                if (state == PathFinder.State.RUNNING) {
                    working = true;
                    continue;
                }
                r.failed = state == PathFinder.State.FAILED;
                r.points = r.failed ? null : r.search.path();
                r.shownFrom = r.searchFrom;
                r.shownTo = r.searchTo;
                r.search = null;
            }
        }
    }
}
