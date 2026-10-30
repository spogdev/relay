package dev.spog.teamlocator.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.spog.teamlocator.TeamLocatorConstants;
import dev.spog.teamlocator.client.ClientState;
import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.TrackedPos;
import dev.spog.teamlocator.client.WorldIdentity;
import dev.spog.teamlocator.client.config.TeamConfig;
import dev.spog.teamlocator.client.path.PathFinder;
import dev.spog.teamlocator.client.path.PathTracker;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the route to each teammate as a line along the ground, see-through so it can be followed
 * round corners and through the floor. Stretches that need an ender pearl are a dashed purple arc
 * from the floor where to throw it, through the gap, to the floor where it lands, with a ring on the
 * floor at each end. The walked stretches have their corners rounded off, so the line reads as a way
 * to go rather than a staircase of block centres, and are drawn as one unbroken strip; the line
 * starts at your feet and curves onto the route, wherever on it you are, and curves off it at the
 * other end to finish at the teammate's feet.
 *
 * <p>The routes come from {@link PathTracker}, which keeps them ready; this only decides when to
 * show them. Holding the Show Paths key always does. Otherwise the Path Finding setting does: always,
 * only while you or the teammate are underground -- in the tunnels the feature is for, and not over
 * open ground where you can see them anyway -- or never, leaving it to the key.
 */
@Environment(EnvType.CLIENT)
public final class PathRenderer {
    /** Plain white, sampled flat; the ping ripple uses it the same way. */
    private static final Identifier WHITE =
            Identifier.fromNamespaceAndPath(TeamLocatorConstants.MOD_ID, "textures/ping_ring.png");
    private static final int FULL_BRIGHT = 0xF000F0;
    /** Lifted off the floor so the line is not buried in the blocks it runs over. */
    private static final double LIFT = 0.12;
    private static final double HALF_WIDTH = 0.06;
    private static final int ALPHA = 190;
    /** Ender pearl purple, for stretches that need one. */
    private static final int PEARL_RGB = 0xB04BFF;
    private static final int ARC_SEGMENTS = 20;
    /** Radius of the ring marking where to throw a pearl from, and of the smaller one where it lands. */
    private static final double THROW_RING = 0.4;
    private static final double LAND_RING = 0.25;
    private static final int RING_SEGMENTS = 20;
    /**
     * Rounds of corner cutting on the walked stretches. Each one replaces every corner with two
     * points a quarter of the way along its sides; three turn a right angle into a smooth bend.
     */
    private static final int SMOOTHING = 3;
    /** How far along the route, in blocks, the line from your feet -- or to theirs -- joins it. */
    private static final double JOIN_AHEAD = 1.5;
    /** Further from the route than this, in blocks, the line does not reach out to you or them. */
    private static final double JOIN_REACH = 6.0;
    /** Smoothed points at each end searched for the one nearest you, or them. */
    private static final int JOIN_SEARCH = 64;
    private static final int JOIN_SEGMENTS = 8;

    /**
     * Each route's walked stretches, smoothed, by the route list they came from. Routes are replaced
     * rather than changed, so the list's identity says whether the smoothing is still good.
     */
    private static final Map<List<PathFinder.Point>, List<List<Vec3>>> smoothed = new IdentityHashMap<>();

    private PathRenderer() {
    }

    public static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                return;
            }
            PoseStack matrices = context.poseStack();
            SubmitNodeCollector collector = context.submitNodeCollector();
            if (matrices == null || collector == null) {
                return;
            }
            TeamConfig config = TeamLocatorClient.CONFIG;
            boolean held = TeamLocatorClient.showPathsHeld();
            boolean selfUnder = PathTracker.underground(
                    mc.level, mc.player.getX(), mc.player.getY(), mc.player.getZ());
            Vec3 cam = mc.gameRenderer.mainCamera().position();
            Map<List<PathFinder.Point>, List<List<Vec3>>> live = new IdentityHashMap<>();
            // Where you are this frame, between ticks, so the join does not lag or jitter.
            Vec3 feet = mc.player.getPosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false))
                    .add(0, LIFT, 0);
            for (TrackedPos teammate : ClientState.onServer(WorldIdentity.current(mc))) {
                List<PathFinder.Point> route = PathTracker.route(teammate.id());
                if (route == null || route.size() < 2) {
                    continue;
                }
                boolean show = held || config.pathFindingMode == TeamConfig.PathFindingMode.ALWAYS;
                if (!show && config.pathFindingMode == TeamConfig.PathFindingMode.UNDERGROUND) {
                    double[] at = PathTracker.position(mc.level, teammate);
                    show = selfUnder || PathTracker.underground(mc.level, at[0], at[1], at[2]);
                }
                if (show) {
                    int rgb = teammate.nameColor(0xFFFFFFFF) & 0xFFFFFF;
                    List<List<Vec3>> walks = smoothed.computeIfAbsent(route, PathRenderer::smoothWalks);
                    live.put(route, walks);
                    Vec3 theirs = theirFeet(mc, teammate);
                    collector.submitCustomGeometry(matrices, RenderTypes.textSeeThrough(WHITE),
                            (pose, buffer) -> drawRoute(buffer, pose, cam, feet, theirs, route, walks, rgb));
                }
            }
            // Keep only what was drawn this frame, so replaced routes do not pile up.
            smoothed.clear();
            smoothed.putAll(live);
        });
    }

    /**
     * Where the teammate's feet are this frame: their player between ticks when it is loaded, as it
     * is drawn, else where the relay last put them.
     */
    private static Vec3 theirFeet(Minecraft mc, TrackedPos teammate) {
        Player entity = mc.level.getPlayerByUUID(teammate.id());
        Vec3 at = entity != null
                ? entity.getPosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false))
                : new Vec3(teammate.x(), teammate.y(), teammate.z());
        return at.add(0, LIFT, 0);
    }

    private static void drawRoute(VertexConsumer buffer, PoseStack.Pose pose, Vec3 cam, Vec3 feet,
                                  Vec3 theirs, List<PathFinder.Point> route, List<List<Vec3>> walks,
                                  int rgb) {
        for (int w = 0; w < walks.size(); w++) {
            List<Vec3> walk = walks.get(w);
            if (w == 0) {
                walk = joinedTo(walk, feet);
            }
            if (w == walks.size() - 1) {
                // The same join at the far end, worked from that end.
                walk = joinedTo(walk.reversed(), theirs).reversed();
            }
            List<Vec3> relative = new ArrayList<>(walk.size());
            for (Vec3 p : walk) {
                relative.add(p.subtract(cam));
            }
            strip(buffer, pose, relative, rgb);
        }
        for (int i = 1; i < route.size(); i++) {
            PathFinder.Point b = route.get(i);
            if (!b.pearl()) {
                continue;
            }
            PathFinder.Point a = route.get(i - 1);
            Vec3 from = new Vec3(a.x(), a.y() + LIFT, a.z()).subtract(cam);
            Vec3 to = new Vec3(b.x(), b.y() + LIFT, b.z()).subtract(cam);
            // From the route on this side, through the middle of the gap, down to the route on the
            // other: a curve through three points, with the gap at its halfway mark. Every other
            // piece is left out, which is what makes it read as dashed.
            Vec3 gap = new Vec3(b.gapX(), b.gapY(), b.gapZ()).subtract(cam);
            Vec3 control = gap.scale(2).subtract(from.add(to).scale(0.5));
            Vec3 prev = from;
            for (int s = 1; s <= ARC_SEGMENTS; s++) {
                double t = s / (double) ARC_SEGMENTS;
                double u = 1 - t;
                Vec3 p = from.scale(u * u).add(control.scale(2 * u * t)).add(to.scale(t * t));
                if (s % 2 == 1) {
                    ribbon(buffer, pose, prev, p, PEARL_RGB);
                }
                prev = p;
            }
            // Where to stand and throw, and where you land.
            ring(buffer, pose, from, THROW_RING, PEARL_RGB);
            ring(buffer, pose, to, LAND_RING, PEARL_RGB);
        }
    }

    /**
     * A stretch starting at someone's feet: a curve from them onto the route a little ahead of the
     * point on it nearest them, then the rest of the route from there. Left as it is when they are
     * well off the route, which is about to be searched again anyway.
     */
    private static List<Vec3> joinedTo(List<Vec3> walk, Vec3 feet) {
        int limit = Math.min(walk.size(), JOIN_SEARCH);
        int nearest = 0;
        double nearestDistance = Double.MAX_VALUE;
        for (int i = 0; i < limit; i++) {
            double d = walk.get(i).distanceToSqr(feet);
            if (d < nearestDistance) {
                nearest = i;
                nearestDistance = d;
            }
        }
        if (nearestDistance > JOIN_REACH * JOIN_REACH) {
            return walk;
        }
        int join = nearest;
        double along = 0;
        while (join < walk.size() - 1 && along < JOIN_AHEAD) {
            along += walk.get(join).distanceTo(walk.get(join + 1));
            join++;
        }
        // A curve from your feet that bends through the nearest point and meets the route heading
        // the way it goes, so the two read as one line.
        Vec3 bend = walk.get(nearest);
        Vec3 end = walk.get(join);
        List<Vec3> out = new ArrayList<>(JOIN_SEGMENTS + walk.size() - join);
        for (int s = 0; s < JOIN_SEGMENTS; s++) {
            double t = s / (double) JOIN_SEGMENTS;
            double u = 1 - t;
            out.add(feet.scale(u * u).add(bend.scale(2 * u * t)).add(end.scale(t * t)));
        }
        out.addAll(walk.subList(join, walk.size()));
        return out;
    }

    /**
     * A line as one unbroken strip facing the camera: each point's edges are shared by the pieces
     * either side of it, turned to the direction through that point, so a bend has no notch in it.
     */
    private static void strip(VertexConsumer buffer, PoseStack.Pose pose, List<Vec3> line, int rgb) {
        int n = line.size();
        if (n < 2) {
            return;
        }
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int bl = rgb & 0xFF;
        Vec3[] side = new Vec3[n];
        Vec3 last = null;
        for (int i = 0; i < n; i++) {
            Vec3 along = line.get(Math.min(i + 1, n - 1)).subtract(line.get(Math.max(i - 1, 0)));
            // Across the line and across the view to this point, which is the point itself: the
            // pose is centred on the camera.
            Vec3 across = along.cross(line.get(i));
            double len = across.length();
            side[i] = len < 1.0e-6 ? last : across.scale(HALF_WIDTH / len);
            last = side[i];
        }
        for (int i = n - 1; i >= 0 && side[i] == null; i--) {
            side[i] = new Vec3(0, 0, 0);
        }
        for (int i = 0; i < n; i++) {
            if (side[i] == null) {
                side[i] = new Vec3(0, 0, 0);
            }
        }
        for (int i = 0; i < n - 1; i++) {
            Vec3 a = line.get(i);
            Vec3 b = line.get(i + 1);
            vertex(buffer, pose, a.add(side[i]), 0, 0, r, g, bl);
            vertex(buffer, pose, a.subtract(side[i]), 1, 0, r, g, bl);
            vertex(buffer, pose, b.subtract(side[i + 1]), 1, 1, r, g, bl);
            vertex(buffer, pose, b.add(side[i + 1]), 0, 1, r, g, bl);
        }
    }

    /**
     * The route's walked stretches -- split where a pearl is thrown -- as lines in the world with
     * their corners rounded. The ends of each stretch stay put, so they still meet the pearl arcs.
     */
    private static List<List<Vec3>> smoothWalks(List<PathFinder.Point> route) {
        List<List<Vec3>> walks = new ArrayList<>();
        List<Vec3> walk = new ArrayList<>();
        for (PathFinder.Point p : route) {
            Vec3 at = new Vec3(p.x(), p.y() + LIFT, p.z());
            if (p.pearl()) {
                walks.add(walk);
                walk = new ArrayList<>();
            }
            walk.add(at);
        }
        walks.add(walk);
        List<List<Vec3>> out = new ArrayList<>(walks.size());
        for (List<Vec3> w : walks) {
            List<Vec3> line = w;
            for (int round = 0; round < SMOOTHING && line.size() > 2; round++) {
                line = cutCorners(line);
            }
            out.add(line);
        }
        return out;
    }

    /**
     * One round of corner cutting: each inside corner becomes two points either side of it.
     *
     * <p>Between points at different heights, a new point takes the higher of the two, not a height
     * in between: a straight cut would slope from the upper block's centre down through its edge,
     * into the block. Holding the height means the line leaves the upper block level and only drops
     * once past its edge -- or, going up, rises before reaching the block in front. Straight up and
     * down, on a ladder or in water, there is no edge to clear, and the height is cut as usual.
     */
    private static List<Vec3> cutCorners(List<Vec3> line) {
        List<Vec3> out = new ArrayList<>(line.size() * 2);
        out.add(line.get(0));
        for (int i = 1; i < line.size(); i++) {
            Vec3 a = line.get(i - 1);
            Vec3 b = line.get(i);
            if (i > 1) {
                out.add(cut(a, b, 0.25));
            }
            if (i < line.size() - 1) {
                out.add(cut(a, b, 0.75));
            }
        }
        out.add(line.get(line.size() - 1));
        return out;
    }

    private static Vec3 cut(Vec3 a, Vec3 b, double t) {
        Vec3 p = a.lerp(b, t);
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        if (dx * dx + dz * dz < 1.0e-4) {
            return p;
        }
        return new Vec3(p.x, Math.max(a.y, b.y), p.z);
    }

    /** A flat ring lying on the floor, centred on a point of the route. */
    private static void ring(VertexConsumer buffer, PoseStack.Pose pose, Vec3 centre, double radius,
                             int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int bl = rgb & 0xFF;
        double inner = radius - 2 * HALF_WIDTH;
        for (int i = 0; i < RING_SEGMENTS; i++) {
            double a0 = i * Math.PI * 2 / RING_SEGMENTS;
            double a1 = (i + 1) * Math.PI * 2 / RING_SEGMENTS;
            Vec3 in0 = centre.add(Math.cos(a0) * inner, 0, Math.sin(a0) * inner);
            Vec3 out0 = centre.add(Math.cos(a0) * radius, 0, Math.sin(a0) * radius);
            Vec3 out1 = centre.add(Math.cos(a1) * radius, 0, Math.sin(a1) * radius);
            Vec3 in1 = centre.add(Math.cos(a1) * inner, 0, Math.sin(a1) * inner);
            // Wound both ways: the layer drops faces turned away, and the ring is seen from above
            // and, through the floor, from below.
            vertex(buffer, pose, in0, 0, 0, r, g, bl);
            vertex(buffer, pose, out0, 1, 0, r, g, bl);
            vertex(buffer, pose, out1, 1, 1, r, g, bl);
            vertex(buffer, pose, in1, 0, 1, r, g, bl);
            vertex(buffer, pose, in1, 0, 1, r, g, bl);
            vertex(buffer, pose, out1, 1, 1, r, g, bl);
            vertex(buffer, pose, out0, 1, 0, r, g, bl);
            vertex(buffer, pose, in0, 0, 0, r, g, bl);
        }
    }

    /** A flat strip from one point to another, turned to face the camera along its length. */
    private static void ribbon(VertexConsumer buffer, PoseStack.Pose pose, Vec3 a, Vec3 b, int rgb) {
        Vec3 along = b.subtract(a);
        Vec3 side = along.cross(a.add(b).scale(0.5));
        double len = side.length();
        if (len < 1.0e-6) {
            return;
        }
        side = side.scale(HALF_WIDTH / len);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int bl = rgb & 0xFF;
        vertex(buffer, pose, a.add(side), 0, 0, r, g, bl);
        vertex(buffer, pose, a.subtract(side), 1, 0, r, g, bl);
        vertex(buffer, pose, b.subtract(side), 1, 1, r, g, bl);
        vertex(buffer, pose, b.add(side), 0, 1, r, g, bl);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, Vec3 p, float u, float v,
                               int r, int g, int b) {
        buffer.addVertex(pose, (float) p.x, (float) p.y, (float) p.z)
                .setColor(r, g, b, ALPHA)
                .setUv(u, v)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, 0.0f, 1.0f, 0.0f);
    }
}
