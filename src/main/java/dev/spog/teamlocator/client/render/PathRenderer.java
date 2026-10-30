package dev.spog.teamlocator.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.spog.teamlocator.TeamLocatorConstants;
import dev.spog.teamlocator.client.ClientState;
import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.TrackedPos;
import dev.spog.teamlocator.client.config.TeamConfig;
import dev.spog.teamlocator.client.path.PathFinder;
import dev.spog.teamlocator.client.path.PathTracker;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Draws the route to each teammate as a line along the ground, see-through so it can be followed
 * round corners and through the floor. Stretches that need an ender pearl are a dashed purple arc
 * from the floor where to throw it, through the gap, to the floor where it lands, with a ring on the
 * floor at each end.
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

    private PathRenderer() {
    }

    public static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                return;
            }
            PoseStack matrices = context.poseStack();
            MultiBufferSource.BufferSource consumers = context.bufferSource();
            if (matrices == null || consumers == null) {
                return;
            }
            TeamConfig config = TeamLocatorClient.CONFIG;
            boolean held = TeamLocatorClient.showPathsHeld();
            boolean selfUnder = PathTracker.underground(
                    mc.level, mc.player.getX(), mc.player.getY(), mc.player.getZ());
            Vec3 cam = mc.gameRenderer.getMainCamera().position();
            PoseStack.Pose pose = matrices.last();
            VertexConsumer buffer = consumers.getBuffer(RenderTypes.textSeeThrough(WHITE));
            for (TrackedPos teammate : ClientState.latest()) {
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
                    drawRoute(buffer, pose, cam, route, rgb);
                }
            }
            consumers.endBatch(RenderTypes.textSeeThrough(WHITE));
        });
    }

    private static void drawRoute(VertexConsumer buffer, PoseStack.Pose pose, Vec3 cam,
                                  List<PathFinder.Point> route, int rgb) {
        for (int i = 1; i < route.size(); i++) {
            PathFinder.Point a = route.get(i - 1);
            PathFinder.Point b = route.get(i);
            Vec3 from = new Vec3(a.x(), a.y() + LIFT, a.z()).subtract(cam);
            Vec3 to = new Vec3(b.x(), b.y() + LIFT, b.z()).subtract(cam);
            if (!b.pearl()) {
                ribbon(buffer, pose, from, to, rgb);
                continue;
            }
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
