package dev.spog.teamlocator.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.spog.teamlocator.client.ClientState;
import dev.spog.teamlocator.client.PingHandler;
import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.TrackedPos;
import dev.spog.teamlocator.client.compat.xaero.XaeroCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;
import java.util.UUID;

/**
 * Draws teammates' floating in-world icons with the mod's own renderer, so they show whether or not
 * Xaero's Minimap is installed. This is the default; a player can hand the job to Xaero's renderer
 * instead ({@link dev.spog.teamlocator.client.config.TeamConfig#useXaeroInWorldIcons}), in which case
 * this steps aside so a teammate never carries two icons.
 *
 * <p><b>Faithful to Xaero's tracked-player in-world icon</b>, whose geometry was read from Xaero's
 * own {@code PlayerTrackerIconRenderer}/{@code PlayerTrackerMinimapElementRenderer} so this is a
 * match rather than an approximation: the player's 8&times;8 skin face (plus the hat overlay) inside
 * a 1px border, a translucent-black name plate above it, billboarded to the camera, drawn at a fixed
 * world size that fades in with distance. The one deliberate difference is the ask: Xaero's border is
 * white, here it is the teammate's ping colour, so the marker is colour-coded to match their pings
 * and HUD row.
 *
 * <p>Drawn see-through, like the mod's pings and Xaero's own in-world icons — a teammate marker is a
 * locator, so a wall between you should not hide it.
 */
@Environment(EnvType.CLIENT)
public final class TeammateIconRenderer {
    // ---- geometry, measured from Xaero's renderer (pixels on the icon's local quad) ----
    /** The skin face is an 8&times;8 quad centred on the origin: x,y run -4..+4. */
    private static final float FACE_HALF = 4.0f;
    /** UV of the face region on a 64&times;64 skin: the head front is the 8&times;8 block at (8,8). */
    private static final float FACE_U0 = 8.0f / 64.0f;
    private static final float FACE_V0 = 8.0f / 64.0f;
    private static final float FACE_U1 = 16.0f / 64.0f;
    private static final float FACE_V1 = 16.0f / 64.0f;
    /** UV of the hat/overlay region: the 8&times;8 block at (40,8). */
    private static final float HAT_U0 = 40.0f / 64.0f;
    private static final float HAT_V0 = 8.0f / 64.0f;
    private static final float HAT_U1 = 48.0f / 64.0f;
    private static final float HAT_V1 = 16.0f / 64.0f;
    /** The border is a 10&times;10 quad behind the face: 1px proud on every side (-5..+5). */
    private static final float BORDER_HALF = 5.0f;
    /** A hair toward the viewer so the coloured border sits behind the face, not z-fighting it. */
    private static final float FACE_Z = -0.01f;
    private static final float HAT_Z = -0.02f;

    /**
     * On-screen size of one icon pixel as a fraction of the icon's distance — a <em>constant apparent
     * size</em>, so the marker looks the same on screen however far the teammate is, exactly like
     * Xaero's in-world icon (and the mod's own pings). A fixed world size instead made it shrink into
     * the distance, which is what read as "doesn't scale". Multiplied by distance per-frame to get the
     * world-space pixel size at that range. 0.0018 puts the 10px border at roughly Xaero's apparent
     * size; it is the single knob for overall icon size.
     */
    private static final float APPARENT_PIXEL = 0.0018f;
    /** Floor on the world pixel size, so a very near icon (right at the fade edge) can't collapse. */
    private static final float MIN_PIXEL = 1.0f / 48.0f;

    /**
     * Feet-to-body-centre lift, so the icon floats at the teammate's chest like Xaero's does rather
     * than at their feet. This is the same 0.9 the Xaero-path mixin applies to Xaero's own icon.
     */
    private static final double BODY_CENTRE_OFFSET = 0.9;

    // ---- distance fade, measured from Xaero (WORLD_MINIMUM_DISTANCE / WORLD_FADING_LENGTH = 10) ----
    /** Nearer than this the icon is hidden: standing next to a teammate you don't need a locator. */
    private static final double FADE_MIN_DISTANCE = 10.0;
    /** Over this band past the minimum the icon fades from invisible to fully opaque. */
    private static final double FADE_LENGTH = 10.0;

    /** Name plate: black background, ~90/255 alpha, exactly as Xaero draws it. */
    private static final int NAME_BG_ALPHA = 90;
    /** The name text sits one icon-pixel above the border's top edge. */
    private static final float NAME_GAP = 1.0f;
    /** Font is 8px tall (ascent+descent ≈ lineHeight-1); the plate is that plus 1px top and bottom. */
    private static final int FULL_BRIGHT = 0xF000F0;

    private TeammateIconRenderer() {
    }

    public static void register() {
        // Same hook and reasoning as PingRenderer: after every translucent terrain layer (water
        // included) is down, so a see-through icon composites over it rather than being covered.
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context -> {
            // Off entirely when the player handed in-world icons to Xaero AND Xaero is present to
            // take them; otherwise the mod always draws its own. Exactly one renderer ever runs.
            if (TeamLocatorClient.CONFIG.useXaeroInWorldIcons && XaeroCompat.isMinimapInstalled()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                return;
            }
            Identifier dimension = mc.player.level().dimension().identifier();
            List<TrackedPos> teammates = ClientState.latest();
            if (teammates.isEmpty()) {
                return;
            }
            PoseStack matrices = context.poseStack();
            MultiBufferSource.BufferSource consumers = context.bufferSource();
            if (matrices == null || consumers == null) {
                return;
            }
            Camera camera = mc.gameRenderer.getMainCamera();
            // The frame's partial tick for entity position, so a loaded teammate is drawn at its
            // smoothly interpolated render position rather than its last whole-tick position. MUST be
            // the paused variant (true): entity tick-positions freeze between ticks, so feeding the
            // realtime partial (false) — which keeps advancing — extrapolated past the real position
            // and snapped back every frame, which is the "bugs backwards and forwards" jitter.
            float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);

            // Skins are per-teammate textures, so each is its own render layer. Flushing a layer
            // mid-loop (the previous approach) dropped the face; instead every icon is queued and the
            // skin layers are all drained together at the end, exactly as PingRenderer flushes once.
            java.util.Set<RenderType> skinLayers = new java.util.HashSet<>();
            for (TrackedPos teammate : teammates) {
                // Positions never cross dimensions, so an entry in another dimension is stale for
                // this view — skip it rather than draw a marker at coordinates in a different world.
                if (!teammate.dimension().equals(dimension)) {
                    continue;
                }
                renderIcon(mc, matrices, consumers, camera, teammate, partial, skinLayers);
            }
            for (RenderType skinLayer : skinLayers) {
                consumers.endBatch(skinLayer);
            }
            consumers.endBatch(RenderTypes.textBackgroundSeeThrough());
            consumers.endLastBatch(); // the font's glyph layer
        });
    }

    private static void renderIcon(Minecraft mc, PoseStack matrices,
                                   MultiBufferSource.BufferSource consumers,
                                   Camera camera, TrackedPos teammate, float partial,
                                   java.util.Set<RenderType> skinLayers) {
        Vec3 cam = camera.position();

        // Prefer the teammate's live entity when it is loaded locally: vanilla interpolates it every
        // frame with the partial tick, so the icon locks onto a moving player smoothly. Xaero does the
        // same. The relay position is only a fallback for teammates out of entity range, where the
        // small ~200ms map lag is invisible anyway. Using the relay's interpolated (delayed) position
        // for everyone was what made the icon trail behind and snap.
        double px;
        double py;
        double pz;
        var entity = mc.level.getPlayerByUUID(teammate.id());
        if (entity != null) {
            px = entity.getX(partial);
            py = entity.getY(partial);
            pz = entity.getZ(partial);
        } else {
            double[] pos = ClientState.interpolated(teammate.id());
            px = pos != null ? pos[0] : teammate.x();
            py = pos != null ? pos[1] : teammate.y();
            pz = pos != null ? pos[2] : teammate.z();
        }

        double dx = px - cam.x;
        double dy = py + BODY_CENTRE_OFFSET - cam.y;
        double dz = pz - cam.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        int alpha = fadeAlpha(distance);
        if (alpha <= 0) {
            return; // inside the near-fade: too close to be worth a locator, exactly like Xaero
        }

        // Constant apparent size: the world-space pixel grows with distance so the icon stays the
        // same size on screen, like Xaero's and the mod's pings. A fixed world size made it shrink
        // away into the distance.
        float pixel = Math.max(MIN_PIXEL, (float) (distance * APPARENT_PIXEL));

        matrices.pushPose();
        matrices.translate(dx, dy, dz);
        matrices.mulPose(camera.rotation());      // billboard toward the camera
        matrices.scale(pixel, pixel, pixel);       // work in icon pixels from here on
        PoseStack.Pose pose = matrices.last();

        // 1) The coloured border: a 10x10 quad behind the face, in the teammate's ping colour. This
        //    is the one change from Xaero, whose border is white.
        int rgb = teammate.nameColor(0xFFFFFFFF) & 0xFFFFFF;
        int br = (rgb >> 16) & 0xFF;
        int bg = (rgb >> 8) & 0xFF;
        int bb = rgb & 0xFF;
        VertexConsumer border = consumers.getBuffer(RenderTypes.textBackgroundSeeThrough());
        colorQuad(border, pose, -BORDER_HALF, -BORDER_HALF, BORDER_HALF, BORDER_HALF, 0.0f,
                br, bg, bb, alpha);

        // 2) The skin face and its hat overlay. Drawn on the ENTITY layer, not the text layer: the
        //    text pipeline samples a font/atlas texture and produced nothing from a player skin (the
        //    "just a coloured box" bug). entityTranslucent is exactly what the player model uses to
        //    draw a skin, so it samples correctly. V is flipped so the face is upright.
        Identifier skin = skinTexture(mc, teammate.id());
        RenderType faceLayer = RenderTypes.entityTranslucent(skin);
        skinLayers.add(faceLayer); // drained once at the end of the frame, not mid-loop
        texQuad(consumers.getBuffer(faceLayer), pose, -FACE_HALF, -FACE_HALF, FACE_HALF, FACE_HALF,
                FACE_Z, FACE_U0, FACE_V1, FACE_U1, FACE_V0, alpha);
        texQuad(consumers.getBuffer(faceLayer), pose, -FACE_HALF, -FACE_HALF, FACE_HALF, FACE_HALF,
                HAT_Z, HAT_U0, HAT_V1, HAT_U1, HAT_V0, alpha);

        // 3) The name plate above the icon.
        drawName(mc, consumers, pose, teammate.id(), alpha);

        matrices.popPose();
    }

    /**
     * Full-opaque past {@link #FADE_MIN_DISTANCE} + {@link #FADE_LENGTH}, fading to nothing across
     * the band below that, and gone under the minimum. Mirrors Xaero's own distance fade so a
     * teammate's icon appears and disappears at the same ranges whichever renderer is drawing it.
     */
    private static int fadeAlpha(double distance) {
        if (distance <= FADE_MIN_DISTANCE) {
            return 0;
        }
        double over = distance - FADE_MIN_DISTANCE;
        if (over >= FADE_LENGTH) {
            return 255;
        }
        return (int) Math.round(255.0 * (over / FADE_LENGTH));
    }

    /**
     * The teammate's skin: the live entity's if it is loaded (so it matches what you see in the
     * world), else the one their {@link PlayerInfo} carries on the connection, else the default skin
     * derived from their UUID while a real one loads. Same resolution order Xaero uses.
     */
    private static Identifier skinTexture(Minecraft mc, UUID id) {
        if (mc.level != null) {
            Player entity = mc.level.getPlayerByUUID(id);
            if (entity instanceof AbstractClientPlayer clientPlayer) {
                return clientPlayer.getSkin().body().texturePath();
            }
        }
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(id);
            if (info != null) {
                PlayerSkin skin = info.getSkin();
                if (skin != null) {
                    return skin.body().texturePath();
                }
            }
        }
        return DefaultPlayerSkin.get(id).body().texturePath();
    }

    /** The name plate: a translucent-black bar sized to the name, then the name centred on it. */
    private static void drawName(Minecraft mc, MultiBufferSource consumers, PoseStack.Pose pose,
                                 UUID id, int alpha) {
        Font font = mc.font;
        String name = PingHandler.displayName(mc, id);
        int textWidth = font.width(name);
        // Plate spans the text plus a 1px margin each side; its bottom sits NAME_GAP above the border.
        float half = textWidth / 2.0f + 1.0f;
        float bottom = BORDER_HALF + NAME_GAP;
        float top = bottom + font.lineHeight;
        int bgAlpha = Math.min(alpha, NAME_BG_ALPHA);
        VertexConsumer plate = consumers.getBuffer(RenderTypes.textBackgroundSeeThrough());
        colorQuad(plate, pose, -half, bottom, half, top, 0.0f, 0, 0, 0, bgAlpha);

        // Text drawn at the plate's inner-top, growing downward. Y is flipped so the glyphs read
        // upright on the upward-Y billboard, exactly as the face UV is flipped above.
        Matrix4f mat = new Matrix4f(pose.pose());
        mat.translate(-textWidth / 2.0f, top - 1.0f, 0.0f);
        mat.scale(1.0f, -1.0f, 1.0f);
        int textArgb = (alpha << 24) | 0xFFFFFF;
        font.drawInBatch(name, 0.0f, 0.0f, textArgb, false, mat, consumers,
                Font.DisplayMode.SEE_THROUGH, 0, FULL_BRIGHT);
    }

    /** A flat coloured quad on the see-through nametag-background layer (POSITION_COLOR_LIGHTMAP). */
    private static void colorQuad(VertexConsumer buffer, PoseStack.Pose pose,
                                  float x0, float y0, float x1, float y1, float z,
                                  int r, int g, int b, int a) {
        buffer.addVertex(pose, x0, y0, z).setColor(r, g, b, a).setLight(FULL_BRIGHT);
        buffer.addVertex(pose, x1, y0, z).setColor(r, g, b, a).setLight(FULL_BRIGHT);
        buffer.addVertex(pose, x1, y1, z).setColor(r, g, b, a).setLight(FULL_BRIGHT);
        buffer.addVertex(pose, x0, y1, z).setColor(r, g, b, a).setLight(FULL_BRIGHT);
    }

    /**
     * A textured white-tinted quad on the entity layer, whose format is
     * POSITION_COLOR_TEXCOORD_OVERLAY_LIGHT_NORMAL — so this sets the overlay (NO_OVERLAY: no damage
     * flash tint) as well, which the text layer did not need. Omitting it wrote a short vertex and was
     * part of why the face never appeared.
     */
    private static void texQuad(VertexConsumer buffer, PoseStack.Pose pose,
                                float x0, float y0, float x1, float y1, float z,
                                float u0, float v0, float u1, float v1, int a) {
        buffer.addVertex(pose, x0, y0, z).setColor(255, 255, 255, a).setUv(u0, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT).setNormal(pose, 0.0f, 0.0f, -1.0f);
        buffer.addVertex(pose, x1, y0, z).setColor(255, 255, 255, a).setUv(u1, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT).setNormal(pose, 0.0f, 0.0f, -1.0f);
        buffer.addVertex(pose, x1, y1, z).setColor(255, 255, 255, a).setUv(u1, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT).setNormal(pose, 0.0f, 0.0f, -1.0f);
        buffer.addVertex(pose, x0, y1, z).setColor(255, 255, 255, a).setUv(u0, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT).setNormal(pose, 0.0f, 0.0f, -1.0f);
    }
}
