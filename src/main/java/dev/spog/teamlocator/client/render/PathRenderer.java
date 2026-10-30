package dev.spog.teamlocator.client.render;

import dev.spog.teamlocator.TeamLocatorConstants;
import dev.spog.teamlocator.client.ClientState;
import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.TrackedPos;
import dev.spog.teamlocator.client.config.TeamConfig;
import dev.spog.teamlocator.client.path.PathFinder;
import dev.spog.teamlocator.client.path.PathTracker;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the route to each teammate as a line along the ground, see-through so it can be followed
 * round corners and through the floor. Stretches that need an ender pearl are a dashed purple arc
 * from the floor where to throw it, through the gap, to the floor where it lands, with a ring on the
 * floor at each end. The walked stretches have their corners rounded off, so the line reads as a way
 * to go rather than a staircase of block centres.
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
            Identifier.of(TeamLocatorConstants.MOD_ID, "textures/ping_ring.png");
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

    /**
     * Each route's walked stretches, smoothed, by the route list they came from. Routes are replaced
     * rather than changed, so the list's identity says whether the smoothing is still good.
     */
    private static final Map<List<PathFinder.Point>, List<List<Vec3d>>> smoothed = new IdentityHashMap<>();

    private PathRenderer() {
    }

    public static void register() {
        // After entities, like the pings on this version: see PingRenderer for why not later.
        WorldRenderEvents.AFTER_ENTITIES.register(PathRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            return;
        }
        MatrixStack matrices = context.matrices();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null) {
            return;
        }
        TeamConfig config = TeamLocatorClient.CONFIG;
        boolean held = TeamLocatorClient.showPathsHeld();
        boolean selfUnder = PathTracker.underground(
                mc.world, mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        Map<List<PathFinder.Point>, List<List<Vec3d>>> live = new IdentityHashMap<>();
        MatrixStack.Entry pose = matrices.peek();
        RenderLayer layer = RenderLayers.textSeeThrough(WHITE);
        VertexConsumer buffer = consumers.getBuffer(layer);
        for (TrackedPos teammate : ClientState.latest()) {
            List<PathFinder.Point> route = PathTracker.route(teammate.id());
            if (route == null || route.size() < 2) {
                continue;
            }
            boolean show = held || config.pathFindingMode == TeamConfig.PathFindingMode.ALWAYS;
            if (!show && config.pathFindingMode == TeamConfig.PathFindingMode.UNDERGROUND) {
                double[] at = PathTracker.position(mc.world, teammate);
                show = selfUnder || PathTracker.underground(mc.world, at[0], at[1], at[2]);
            }
            if (show) {
                int rgb = teammate.nameColor(0xFFFFFFFF) & 0xFFFFFF;
                List<List<Vec3d>> walks = smoothed.computeIfAbsent(route, PathRenderer::smoothWalks);
                live.put(route, walks);
                drawRoute(buffer, pose, cam, route, walks, rgb);
            }
        }
        // Keep only what was drawn this frame, so replaced routes do not pile up.
        smoothed.clear();
        smoothed.putAll(live);
        if (consumers instanceof VertexConsumerProvider.Immediate immediate) {
            immediate.draw(layer);
        }
    }

    private static void drawRoute(VertexConsumer buffer, MatrixStack.Entry pose, Vec3d cam,
                                  List<PathFinder.Point> route, List<List<Vec3d>> walks, int rgb) {
        for (List<Vec3d> walk : walks) {
            for (int i = 1; i < walk.size(); i++) {
                ribbon(buffer, pose, walk.get(i - 1).subtract(cam), walk.get(i).subtract(cam), rgb);
            }
        }
        for (int i = 1; i < route.size(); i++) {
            PathFinder.Point b = route.get(i);
            if (!b.pearl()) {
                continue;
            }
            PathFinder.Point a = route.get(i - 1);
            Vec3d from = new Vec3d(a.x(), a.y() + LIFT, a.z()).subtract(cam);
            Vec3d to = new Vec3d(b.x(), b.y() + LIFT, b.z()).subtract(cam);
            // From the route on this side, through the middle of the gap, down to the route on the
            // other: a curve through three points, with the gap at its halfway mark. Every other
            // piece is left out, which is what makes it read as dashed.
            Vec3d gap = new Vec3d(b.gapX(), b.gapY(), b.gapZ()).subtract(cam);
            Vec3d control = gap.multiply(2).subtract(from.add(to).multiply(0.5));
            Vec3d prev = from;
            for (int s = 1; s <= ARC_SEGMENTS; s++) {
                double t = s / (double) ARC_SEGMENTS;
                double u = 1 - t;
                Vec3d p = from.multiply(u * u).add(control.multiply(2 * u * t)).add(to.multiply(t * t));
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
     * The route's walked stretches -- split where a pearl is thrown -- as lines in the world with
     * their corners rounded. The ends of each stretch stay put, so they still meet the pearl arcs.
     */
    private static List<List<Vec3d>> smoothWalks(List<PathFinder.Point> route) {
        List<List<Vec3d>> walks = new ArrayList<>();
        List<Vec3d> walk = new ArrayList<>();
        for (PathFinder.Point p : route) {
            Vec3d at = new Vec3d(p.x(), p.y() + LIFT, p.z());
            if (p.pearl()) {
                walks.add(walk);
                walk = new ArrayList<>();
            }
            walk.add(at);
        }
        walks.add(walk);
        List<List<Vec3d>> out = new ArrayList<>(walks.size());
        for (List<Vec3d> w : walks) {
            List<Vec3d> line = w;
            for (int round = 0; round < SMOOTHING && line.size() > 2; round++) {
                line = cutCorners(line);
            }
            out.add(line);
        }
        return out;
    }

    /** One round of corner cutting: each inside corner becomes two points either side of it. */
    private static List<Vec3d> cutCorners(List<Vec3d> line) {
        List<Vec3d> out = new ArrayList<>(line.size() * 2);
        out.add(line.get(0));
        for (int i = 1; i < line.size(); i++) {
            Vec3d a = line.get(i - 1);
            Vec3d b = line.get(i);
            if (i > 1) {
                out.add(a.lerp(b, 0.25));
            }
            if (i < line.size() - 1) {
                out.add(a.lerp(b, 0.75));
            }
        }
        out.add(line.get(line.size() - 1));
        return out;
    }

    /** A flat ring lying on the floor, centred on a point of the route. */
    private static void ring(VertexConsumer buffer, MatrixStack.Entry pose, Vec3d centre, double radius,
                             int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int bl = rgb & 0xFF;
        double inner = radius - 2 * HALF_WIDTH;
        for (int i = 0; i < RING_SEGMENTS; i++) {
            double a0 = i * Math.PI * 2 / RING_SEGMENTS;
            double a1 = (i + 1) * Math.PI * 2 / RING_SEGMENTS;
            Vec3d in0 = centre.add(Math.cos(a0) * inner, 0, Math.sin(a0) * inner);
            Vec3d out0 = centre.add(Math.cos(a0) * radius, 0, Math.sin(a0) * radius);
            Vec3d out1 = centre.add(Math.cos(a1) * radius, 0, Math.sin(a1) * radius);
            Vec3d in1 = centre.add(Math.cos(a1) * inner, 0, Math.sin(a1) * inner);
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
    private static void ribbon(VertexConsumer buffer, MatrixStack.Entry pose, Vec3d a, Vec3d b, int rgb) {
        Vec3d along = b.subtract(a);
        Vec3d side = along.crossProduct(a.add(b).multiply(0.5));
        double len = side.length();
        if (len < 1.0e-6) {
            return;
        }
        side = side.multiply(HALF_WIDTH / len);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int bl = rgb & 0xFF;
        vertex(buffer, pose, a.add(side), 0, 0, r, g, bl);
        vertex(buffer, pose, a.subtract(side), 1, 0, r, g, bl);
        vertex(buffer, pose, b.subtract(side), 1, 1, r, g, bl);
        vertex(buffer, pose, b.add(side), 0, 1, r, g, bl);
    }

    private static void vertex(VertexConsumer buffer, MatrixStack.Entry pose, Vec3d p, float u, float v,
                               int r, int g, int b) {
        buffer.vertex(pose, (float) p.x, (float) p.y, (float) p.z)
                .color(r, g, b, ALPHA)
                .texture(u, v)
                .light(FULL_BRIGHT)
                .normal(pose, 0.0f, 1.0f, 0.0f);
    }
}
