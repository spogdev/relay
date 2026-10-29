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

import java.util.List;

/**
 * Draws the route to each teammate as a line along the ground, see-through so it can be followed
 * round corners and through the floor. Stretches that need an ender pearl are a dashed purple arc
 * from where to throw it to where it lands.
 *
 * <p>The routes come from {@link PathTracker}, which keeps them ready; this only decides when to
 * show them. Holding the Show Paths key always does. Otherwise the Path Finding setting does: always,
 * or only while you or the teammate are underground -- in the tunnels the feature is for, and not
 * over open ground where you can see them anyway.
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
    /** How high a pearl's arc rises above the straight line between its ends. */
    private static final double ARC_HEIGHT = 0.6;
    private static final int ARC_SEGMENTS = 12;
    /** Where on a pearl's arc it is thrown from: about eye height. */
    private static final double THROW_HEIGHT = 1.5;

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
        MatrixStack.Entry pose = matrices.peek();
        RenderLayer layer = RenderLayers.textSeeThrough(WHITE);
        VertexConsumer buffer = consumers.getBuffer(layer);
        for (TrackedPos teammate : ClientState.latest()) {
            List<PathFinder.Point> route = PathTracker.route(teammate.id());
            if (route == null || route.size() < 2) {
                continue;
            }
            boolean show = held || config.pathFindingMode == TeamConfig.PathFindingMode.ALWAYS;
            if (!show) {
                double[] at = PathTracker.position(mc.world, teammate);
                show = selfUnder || PathTracker.underground(mc.world, at[0], at[1], at[2]);
            }
            if (show) {
                int rgb = teammate.nameColor(0xFFFFFFFF) & 0xFFFFFF;
                drawRoute(buffer, pose, cam, route, rgb);
            }
        }
        if (consumers instanceof VertexConsumerProvider.Immediate immediate) {
            immediate.draw(layer);
        }
    }

    private static void drawRoute(VertexConsumer buffer, MatrixStack.Entry pose, Vec3d cam,
                                  List<PathFinder.Point> route, int rgb) {
        for (int i = 1; i < route.size(); i++) {
            PathFinder.Point a = route.get(i - 1);
            PathFinder.Point b = route.get(i);
            Vec3d from = new Vec3d(a.x(), a.y() + LIFT, a.z()).subtract(cam);
            Vec3d to = new Vec3d(b.x(), b.y() + LIFT, b.z()).subtract(cam);
            if (!b.pearl()) {
                ribbon(buffer, pose, from, to, rgb);
                continue;
            }
            // Thrown from about eye height, falling to where the player will stand. Every other
            // piece is left out, which is what makes it read as dashed.
            Vec3d start = from.add(0, THROW_HEIGHT - LIFT, 0);
            Vec3d prev = start;
            for (int s = 1; s <= ARC_SEGMENTS; s++) {
                double t = s / (double) ARC_SEGMENTS;
                Vec3d p = start.lerp(to, t).add(0, ARC_HEIGHT * 4 * t * (1 - t), 0);
                if (s % 2 == 1) {
                    ribbon(buffer, pose, prev, p, PEARL_RGB);
                }
                prev = p;
            }
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
