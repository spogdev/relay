package dev.spog.teamlocator.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.spog.teamlocator.TeamLocatorConstants;
import dev.spog.teamlocator.client.PingHandler;
import dev.spog.teamlocator.client.PingState;
import dev.spog.teamlocator.client.TeamLocatorClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import dev.spog.teamlocator.client.gui.SecondsSlider;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Draws teammates' location pings in the world as billboarded markers: a coloured pin, the pinger's
 * name to its left and the distance to its right, and a one-shot coloured ripple when it lands.
 *
 * <p>Deliberately <b>not</b> routed through Xaero — pings are their own feature and must work
 * whether or not those mods are installed, so this is a plain Fabric level-render hook.
 *
 * <p>The pin and labels keep a <b>constant apparent size</b> at any distance (like an in-world
 * waypoint icon): a far ping is pulled in to a fixed range along the line toward it, so it is always
 * on screen pointing at the spot rather than a speck on the horizon. Because everything is drawn in
 * world space, a zoom (spyglass, a zoom mod) magnifies the markers exactly as it magnifies Xaero's
 * icons — the projection does it for free.
 *
 * <p>Everything is drawn see-through (no depth test) so a ping stays visible through terrain, which
 * is the point of a callout.
 */
@Environment(EnvType.CLIENT)
public final class PingRenderer {
    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(TeamLocatorConstants.MOD_ID, "textures/ping.png");
    private static final Identifier RING =
            Identifier.fromNamespaceAndPath(TeamLocatorConstants.MOD_ID, "textures/ping_ring.png");

    /** The marker's on-screen size as a fraction of its draw distance; constant apparent size. */
    private static final double APPARENT_SIZE = 0.055;
    /** Lifted off the surface so a pin on the ground isn't half-buried. */
    private static final float Y_OFFSET = 0.65f;
    private static final int FULL_BRIGHT = 0xF000F0;
    /**
     * A far ping is drawn at this range along the direction to it, so a ping shown while looking at
     * it is on screen rather than a speck on the horizon.
     */
    private static final double CLAMP_DISTANCE = 24.0;
    /** Never draw closer than this, so a ping on top of the camera doesn't fill the view. */
    private static final double MIN_DISTANCE = 2.0;
    /**
     * Floor on the marker's world-space size, so however the draw distance works out the pin never
     * renders smaller than this — the actual "minimum size" clamp. Applied after the distance math.
     */
    private static final float MIN_SCALE = 0.9f;

    /**
     * How close to the crosshair a ping must be for its <em>text</em> to show, as the cosine of the
     * angle between the look vector and the direction to the ping. ~0.999 is about 2.5° — roughly
     * the crosshair actually hovering over the pin, not merely glancing its way. The pin itself is
     * always drawn; only the name/distance labels are gated. A little hysteresis stops the text
     * flickering when the crosshair sits right on the edge.
     */
    private static final double SHOW_COS = 0.999;
    private static final double HIDE_COS = 0.997;

    /** How long the placement ripple lasts, in milliseconds. */
    private static final long RIPPLE_MS = 900L;
    /** The ripple's final radius, in marker-widths. */
    private static final float RIPPLE_MAX_RADIUS = 1.8f;
    private static final int RIPPLE_SEGMENTS = 32;
    private static final float RIPPLE_THICKNESS = 0.12f;

    /** Text size in world units, scaled from the 1-unit marker so it sits beside it. */
    private static final float TEXT_SCALE = 0.04f;
    /**
     * Half-width of the pin's actual artwork on its quad. The texture is 16px wide but the opaque
     * pin only spans x=2..13, so the art occupies the middle 0.75 of a quad that runs -0.5..+0.5 —
     * its visible edge is at 0.375, not 0.5. Measuring labels from here rather than the quad edge is
     * what stops them floating away from the icon with a band of transparent padding between.
     */
    private static final float PIN_EDGE = 0.375f;
    /**
     * Gap between the pin's visible edge and the nearest edge of each label BOX, in marker-widths.
     * Zero puts the box flush against the artwork; negative overlaps it slightly. The text itself is
     * inset a further {@link #TAG_PAD_X} inside the box, so this is measured to the box, not to the
     * glyphs. At -1.5*TEXT_SCALE each label overlaps the pin's artwork by a pixel and a half — just
     * enough to read as attached to the icon without burying its edges. The overlap is hidden: the
     * pin is drawn in a later pass, so it paints over whatever tucks under it.
     */
    private static final float LABEL_GAP = -1.5f * TEXT_SCALE;
    /**
     * Vertical offset of the labels from the marker centre, in marker-widths. Positive is up; a
     * small value sits them roughly level with the pin's mid/lower body rather than up as a header.
     */
    private static final float LABEL_RAISE = 0.05f;
    /** Padding around the text inside each label's background box, in world units. */
    private static final float TAG_PAD_X = 0.08f;
    private static final float TAG_PAD_Y = 0.05f;
    /** Vanilla-nametag translucent black, as ARGB — the box drawn around each label. */
    private static final int TAG_BG = 0x40000000;
    /**
     * Depth step that seats the label boxes just behind their glyphs. The billboard faces the camera
     * down -Z (the pin's normal is 0,0,-1), so a positive Z pushes the box away from the viewer.
     */
    private static final float TAG_Z = 0.01f;
    /**
     * Depth of the pin quad. Negative is toward the viewer (the billboard faces down -Z), so the pin
     * paints in front of the label boxes that tuck under its edges.
     */
    private static final float PIN_Z = -0.01f;
    /** How much of the ping's colour the distance label keeps; the rest is darkened away. */
    private static final float DIST_DARKEN = 0.75f;
    /** How far the label text is lifted toward white, so dark ping colours stay readable. */
    private static final float TEXT_BRIGHTEN = 0.45f;

    /**
     * Per-ping "currently shown" latch for the crosshair gate, so the show/hide angles can differ
     * (hysteresis) and the marker doesn't strobe when the ping sits right at the edge of the cone.
     */
    private static final java.util.Map<java.util.UUID, Boolean> shown =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Whether this ping's labels are currently showing, i.e. the crosshair is over it.
     *
     * <p>Read by the ping keybind so pressing it again while looking at your own ping removes it
     * rather than placing another. The latch is what the renderer already uses to decide whether to
     * draw the name and distance, so "is it labelled" and "would pressing the key remove it" can
     * never disagree — the player's cue and the action are driven by the same state.
     */
    public static boolean isHovered(java.util.UUID owner) {
        return shown.getOrDefault(owner, false);
    }

    /**
     * Whether pings ignore depth this frame. Read once at the top of the hook and used by the layer
     * helpers below, so every part of a ping — pin, ripple, label boxes and glyphs — agrees; mixing
     * depth-tested and see-through layers within one marker would let a wall hide the pin while its
     * label floated in front of it.
     */
    private static boolean throughWalls = true;

    /** The textured layer for markers: see-through, or depth-tested when the option is off. */
    private static net.minecraft.client.renderer.rendertype.RenderType markerLayer(Identifier texture) {
        return throughWalls ? RenderTypes.textSeeThrough(texture) : RenderTypes.text(texture);
    }

    /** The untextured layer for label boxes, matching {@link #markerLayer}'s depth behaviour. */
    private static net.minecraft.client.renderer.rendertype.RenderType boxLayer() {
        return throughWalls ? RenderTypes.textBackgroundSeeThrough() : RenderTypes.textBackground();
    }

    /** Glyph display mode, matching {@link #markerLayer}'s depth behaviour. */
    private static Font.DisplayMode glyphMode() {
        return throughWalls ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;
    }

    private PingRenderer() {
    }

    public static void register() {
        // After translucent TERRAIN, not features: water is translucent terrain and renders in a
        // later pass than AFTER_TRANSLUCENT_FEATURES, so drawing there let water cover the marker.
        // This hook runs once every terrain layer — water included — is down, so a see-through ping
        // composites over it as intended.
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context -> {
            var config = TeamLocatorClient.CONFIG;
            if (!config.showPings) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                return;
            }
            // Latch the depth mode for this whole frame, so every layer a ping uses agrees.
            throughWalls = config.pingsThroughWalls;
            // Infinite means "until replaced or disconnect": PingState drops a ping once it is older
            // than the lifetime, so Long.MAX_VALUE is the natural encoding of never. Passing the -1
            // sentinel through unscaled would make every ping instantly expired instead.
            long lifetime = config.pingDisplaySeconds == SecondsSlider.INFINITE
                    ? Long.MAX_VALUE
                    : config.pingDisplaySeconds * 1000L;
            List<PingState.Ping> pings = PingState.visible(
                    mc.player.level().dimension().identifier(), lifetime, config.mutedPingSet());
            // Forget latch state for pings that no longer exist, so the map can't grow unbounded.
            java.util.Set<java.util.UUID> live = new java.util.HashSet<>();
            for (PingState.Ping p : pings) {
                live.add(p.owner());
            }
            shown.keySet().retainAll(live);
            if (pings.isEmpty()) {
                return;
            }
            PoseStack matrices = context.poseStack();
            MultiBufferSource.BufferSource consumers = context.bufferSource();
            if (matrices == null || consumers == null) {
                return;
            }
            Camera camera = mc.gameRenderer.getMainCamera();
            org.joml.Vector3fc fwd = camera.forwardVector();
            Vec3 look = new Vec3(fwd.x(), fwd.y(), fwd.z());
            // Two passes, because the label boxes overlap the pin and the pin must win there.
            //
            // These layers are see-through, so what lands on top is decided by draw order, not by
            // depth. Ordering by endBatch calls alone doesn't work: endLastBatch flushes whichever
            // shared type was used most recently, so with the pin's batch still open it drained the
            // pin together with the glyphs and the later pin flush was a no-op — which is why the
            // boxes kept showing through the icon.
            //
            // So: queue every ping's labels and flush them completely, then queue the pins. Nothing
            // else is open by then, so the pins genuinely paint last.
            for (PingState.Ping ping : pings) {
                renderLabels(mc, matrices, consumers, camera, look, ping);
            }
            consumers.endBatch(boxLayer());
            consumers.endLastBatch(); // drains the font's glyph layer

            for (PingState.Ping ping : pings) {
                renderMarker(mc, matrices, consumers, camera, ping);
            }
            consumers.endBatch(markerLayer(RING));
            consumers.endBatch(markerLayer(TEXTURE));
        });
    }

    /**
     * Sets up the billboarded, distance-clamped transform for a ping and pushes it onto the stack.
     * The caller must pop. Shared by both render passes so the labels and the pin land on exactly
     * the same billboard — computing it twice risks the two drifting apart.
     */
    private static PoseStack.Pose pushBillboard(PoseStack matrices, Camera camera,
                                                PingState.Ping ping, double trueDistance,
                                                double dx, double dy, double dz) {
        // Clamp the DRAW position toward the camera so the marker stays a constant apparent size and
        // on screen. Direction is preserved; only the distance changes.
        double drawDist = Math.max(MIN_DISTANCE, Math.min(trueDistance, CLAMP_DISTANCE));
        if (trueDistance > 1.0e-4) {
            double f = drawDist / trueDistance;
            dx *= f;
            dy *= f;
            dz *= f;
        }
        // Apparent size scales with draw distance, then a hard floor so it never renders too small.
        float scale = Math.max(MIN_SCALE, (float) (drawDist * APPARENT_SIZE));

        matrices.pushPose();
        matrices.translate(dx, dy, dz);
        matrices.mulPose(camera.rotation()); // billboard toward the camera
        matrices.scale(scale, scale, scale);
        return matrices.last();
    }

    /** Camera-relative offset to a ping's marker position. */
    private static Vec3 offsetTo(Camera camera, PingState.Ping ping) {
        Vec3 cam = camera.position();
        return new Vec3(ping.x() - cam.x, ping.y() + Y_OFFSET - cam.y, ping.z() - cam.z);
    }

    /**
     * First pass: the name/distance labels and their boxes, drawn only while the crosshair is over
     * the pin. Queued before the markers so the pin paints over the pixel of box that tucks under it.
     */
    private static void renderLabels(Minecraft mc, PoseStack matrices, MultiBufferSource consumers,
                                     Camera camera, Vec3 look, PingState.Ping ping) {
        int alpha = 255;
        Vec3 d = offsetTo(camera, ping);
        double trueDistance = Math.sqrt(d.x * d.x + d.y * d.y + d.z * d.z);

        // Only the labels are gated on the crosshair; the marker itself always draws in pass two.
        if (!passesCrosshairGate(ping.owner(), look, d.x, d.y, d.z, trueDistance)) {
            return;
        }

        PoseStack.Pose pose = pushBillboard(matrices, camera, ping, trueDistance, d.x, d.y, d.z);
        // The distance is the true straight-line distance, not the clamped draw distance, so it
        // always tells the truth about how far the spot is.
        String name = PingHandler.displayName(mc, ping.owner());
        String dist = Math.round(trueDistance) + "m";
        drawLabels(mc.font, consumers, pose, name, dist, alpha, ping.argb());
        matrices.popPose();
    }

    /**
     * Second pass: the pin and its placement ripple. Queued after every label so the icon paints on
     * top of the boxes overlapping it.
     */
    private static void renderMarker(Minecraft mc, PoseStack matrices, MultiBufferSource consumers,
                                     Camera camera, PingState.Ping ping) {
        // No fade: a ping is fully opaque for its whole life and then is simply gone. PingState
        // already drops it once expired, so anything reaching here should be drawn at full alpha.
        int alpha = 255;
        Vec3 d = offsetTo(camera, ping);
        double trueDistance = Math.sqrt(d.x * d.x + d.y * d.y + d.z * d.z);

        int argb = ping.argb();
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        PoseStack.Pose pose = pushBillboard(matrices, camera, ping, trueDistance, d.x, d.y, d.z);

        // Placement ripple: a ring in the ping's colour that expands once and fades, then is gone.
        renderRipple(consumers, pose, ping, r, g, b, alpha);

        // The pin itself, pulled toward the camera (PIN_Z is negative; the billboard faces down -Z).
        VertexConsumer pin = consumers.getBuffer(markerLayer(TEXTURE));
        float half = 0.5f;
        vertex(pin, pose, -half, -half, PIN_Z, 0.0f, 1.0f, r, g, b, alpha);
        vertex(pin, pose, half, -half, PIN_Z, 1.0f, 1.0f, r, g, b, alpha);
        vertex(pin, pose, half, half, PIN_Z, 1.0f, 0.0f, r, g, b, alpha);
        vertex(pin, pose, -half, half, PIN_Z, 0.0f, 0.0f, r, g, b, alpha);

        matrices.popPose();
    }

    /**
     * Name to the left of the pin, distance to the right, each wrapped in its own vanilla-style
     * translucent nametag box. Raised above centre and drawn see-through so both stay readable
     * through walls, matching the pin. Each side is drawn as a background box then the glyphs on top.
     */
    private static void drawLabels(Font font, MultiBufferSource consumers, PoseStack.Pose pose,
                                   String name, String dist, int alpha, int rgb) {
        // Both labels take the ping's own colour, so a callout reads as one object; the distance is
        // supporting detail, so it takes a darkened version of the same hue rather than a grey.
        // The palette is UUID-derived, so plenty of ping colours are genuinely dark (a muted navy is
        // as likely as a bright green) and read badly as small text on a translucent black box.
        // Lifting toward white keeps the hue that ties the label to its pin while guaranteeing the
        // text is legible whatever colour the player ended up with.
        int bright = brighten(rgb, TEXT_BRIGHTEN);
        int textArgb = (alpha << 24) | (bright & 0xFFFFFF);
        int distArgb = (alpha << 24) | (darken(bright, DIST_DARKEN) & 0xFFFFFF);
        // Raise the labels above the marker centre and vertically centre the glyphs on that line.
        float textY = LABEL_RAISE - font.lineHeight * TEXT_SCALE / 2.0f;

        // Offsets are measured to the BOX edge. The pin texture is mostly transparent padding around
        // the actual pin art, so PIN_EDGE — not the 0.5 quad edge — is where the icon visually ends;
        // butting the boxes against the quad edge would leave an obvious floating gap.
        float nameW = font.width(name) * TEXT_SCALE;
        // Name box: its right edge lands on the pin's left edge, so the text starts a box-width
        // plus one padding further left.
        float nameBoxRight = -PIN_EDGE - LABEL_GAP;
        drawBoxedLabel(font, consumers, pose, name,
                nameBoxRight - TAG_PAD_X - nameW, textY, textArgb, alpha);

        // Distance box: its left edge lands on the pin's right edge. The glyphs sit a pixel right of
        // where the box places them — the box itself stays put.
        float distBoxLeft = PIN_EDGE + LABEL_GAP;
        drawBoxedLabel(font, consumers, pose, dist,
                distBoxLeft + TAG_PAD_X, textY, distArgb, alpha, TEXT_SCALE);
    }

    /**
     * One label: a translucent nametag box sized to the text, then the glyphs. {@code textLeft}/
     * {@code textY} are the world-space top-left of the text; the box is padded around it.
     */
    private static void drawBoxedLabel(Font font, MultiBufferSource consumers, PoseStack.Pose pose,
                                       String text, float textLeft, float textY, int textArgb,
                                       int alpha) {
        drawBoxedLabel(font, consumers, pose, text, textLeft, textY, textArgb, alpha, 0.0f);
    }

    /**
     * As above, with {@code textNudgeX} shifting only the glyphs inside the box. The box is still
     * sized and placed from {@code textLeft}, so a nudge re-centres the text within its background
     * without moving the background itself.
     */
    private static void drawBoxedLabel(Font font, MultiBufferSource consumers, PoseStack.Pose pose,
                                       String text, float textLeft, float textY, int textArgb,
                                       int alpha, float textNudgeX) {
        float w = font.width(text) * TEXT_SCALE;
        float h = font.lineHeight * TEXT_SCALE;

        int bgAlpha = Math.min(alpha, (TAG_BG >>> 24));
        int bg = (bgAlpha << 24) | (TAG_BG & 0xFFFFFF);
        // Box padded around the glyph rectangle. Y grows upward in world space; the text is drawn
        // downward from textY + h (baseline), so the glyph rectangle spans [textY, textY + h].
        fillQuad(consumers, pose,
                textLeft - TAG_PAD_X, textY - TAG_PAD_Y,
                textLeft + w + TAG_PAD_X, textY + h + TAG_PAD_Y, bg);

        Matrix4f mat = new Matrix4f(pose.pose());
        // The nudge is applied here only, after the box above has been placed from the unnudged
        // textLeft — that is what lets the glyphs shift inside a stationary background.
        mat.translate(textLeft + textNudgeX, textY + h, 0.0f); // +h: baseline sits at glyph bottom
        mat.scale(TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE); // -Y: text y grows downward
        font.drawInBatch(text, 0.0f, 0.0f, textArgb, false, mat, consumers,
                glyphMode(), 0, FULL_BRIGHT);
    }

    /**
     * Scales a colour's RGB channels toward black, keeping {@code factor} of each. Multiplicative
     * rather than a fixed subtraction so the hue is preserved and an already-dark ping colour can't
     * underflow into a different-looking shade.
     */
    /**
     * Blends a colour toward white by {@code amount} (0 = unchanged, 1 = white). Used to keep label
     * text readable when the ping's own colour is dark, without losing the hue that identifies it.
     */
    private static int brighten(int rgb, float amount) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        r = Math.round(r + (255 - r) * amount);
        g = Math.round(g + (255 - g) * amount);
        b = Math.round(b + (255 - b) * amount);
        return (r << 16) | (g << 8) | b;
    }

    private static int darken(int rgb, float factor) {
        int r = Math.round(((rgb >> 16) & 0xFF) * factor);
        int g = Math.round(((rgb >> 8) & 0xFF) * factor);
        int b = Math.round((rgb & 0xFF) * factor);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * An untextured translucent quad on the see-through nametag-background layer, ARGB colour.
     * Seated at {@link #TAG_Z}, behind both the glyphs and the pin.
     */
    private static void fillQuad(MultiBufferSource consumers, PoseStack.Pose pose,
                                 float x0, float y0, float x1, float y1, int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        VertexConsumer buffer = consumers.getBuffer(boxLayer());
        // Wind BL -> BR -> TR -> TL to match the pin quad, so the box faces the camera and isn't
        // culled by the background pipeline's back-face culling (the earlier order was reversed,
        // which is why the box didn't render at all).
        colorVertex(buffer, pose, x0, y0, r, g, b, a);
        colorVertex(buffer, pose, x1, y0, r, g, b, a);
        colorVertex(buffer, pose, x1, y1, r, g, b, a);
        colorVertex(buffer, pose, x0, y1, r, g, b, a);
    }

    /**
     * Position-colour-lightmap vertex for the nametag-background layer. That layer's format is
     * POSITION_COLOR_LIGHTMAP — position, colour and light, with no normal — so this deliberately
     * omits setNormal (calling it would write into an element the format doesn't have).
     *
     * <p>Seated at {@link #TAG_Z}, a small step away from the camera, so the glyphs at z=0 paint in
     * front of the box no matter what order the shared buffer flushes its layers in.
     */
    private static void colorVertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y,
                                    int r, int g, int b, int a) {
        buffer.addVertex(pose, x, y, TAG_Z)
                .setColor(r, g, b, a)
                .setLight(FULL_BRIGHT);
    }

    /** A one-shot expanding ring, only in the first {@link #RIPPLE_MS} of the ping's life. */
    private static void renderRipple(MultiBufferSource consumers, PoseStack.Pose pose,
                                     PingState.Ping ping, int r, int g, int b, int pingAlpha) {
        long elapsed = System.currentTimeMillis() - ping.placedAtMillis();
        if (elapsed < 0 || elapsed >= RIPPLE_MS) {
            return;
        }
        float t = elapsed / (float) RIPPLE_MS;
        float radius = t * RIPPLE_MAX_RADIUS;
        int rippleAlpha = Math.min(pingAlpha, Math.round((1.0f - t) * 255.0f));
        if (rippleAlpha <= 0) {
            return;
        }
        VertexConsumer buffer = consumers.getBuffer(markerLayer(RING));
        float inner = Math.max(0.0f, radius - RIPPLE_THICKNESS);
        float outer = radius + RIPPLE_THICKNESS;
        for (int i = 0; i < RIPPLE_SEGMENTS; i++) {
            double a0 = (i / (double) RIPPLE_SEGMENTS) * Math.PI * 2.0;
            double a1 = ((i + 1) / (double) RIPPLE_SEGMENTS) * Math.PI * 2.0;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            // A trapezoid segment of the annulus; the whole white texture is sampled flat.
            vertex(buffer, pose, c0 * inner, s0 * inner, 0.0f, 0.0f, r, g, b, rippleAlpha);
            vertex(buffer, pose, c0 * outer, s0 * outer, 1.0f, 0.0f, r, g, b, rippleAlpha);
            vertex(buffer, pose, c1 * outer, s1 * outer, 1.0f, 1.0f, r, g, b, rippleAlpha);
            vertex(buffer, pose, c1 * inner, s1 * inner, 0.0f, 1.0f, r, g, b, rippleAlpha);
        }
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y,
                               float u, float v, int r, int g, int b, int a) {
        vertex(buffer, pose, x, y, 0.0f, u, v, r, g, b, a);
    }

    /** As above, at an explicit depth — used to seat the pin in front of its label boxes. */
    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z,
                               float u, float v, int r, int g, int b, int a) {
        buffer.addVertex(pose, x, y, z)
                .setColor(r, g, b, a)
                .setUv(u, v)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, 0.0f, 0.0f, -1.0f);
    }

    /**
     * True while the crosshair is over the ping — gates only the labels, not the marker. The
     * direction to the ping is compared to the look vector by dot product; a latch with separate
     * show/hide angles gives a little hysteresis so the text does not strobe when the crosshair
     * sits right on the edge of the pin.
     */
    private static boolean passesCrosshairGate(java.util.UUID owner, Vec3 look,
                                               double dx, double dy, double dz, double distance) {
        if (look == null || distance < 1.0e-4) {
            return true; // no look vector (shouldn't happen) or ping on top of us: show the labels
        }
        double dot = (look.x * dx + look.y * dy + look.z * dz) / distance;
        boolean currentlyShown = shown.getOrDefault(owner, false);
        boolean nowShown = currentlyShown ? dot >= HIDE_COS : dot >= SHOW_COS;
        shown.put(owner, nowShown);
        return nowShown;
    }
}
