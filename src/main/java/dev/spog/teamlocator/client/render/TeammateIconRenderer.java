package dev.spog.teamlocator.client.render;

import dev.spog.teamlocator.client.ClientState;
import dev.spog.teamlocator.client.PingHandler;
import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.TrackedPos;
import dev.spog.teamlocator.client.compat.xaero.XaeroCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.List;
import java.util.UUID;

/**
 * Draws teammates' in-world icons with the mod's own renderer, so they show whether or not Xaero's
 * Minimap is installed. This is the default; a player can hand the job to Xaero's renderer instead
 * ({@link dev.spog.teamlocator.client.config.TeamConfig#useXaeroInWorldIcons}), in which case this
 * steps aside so a teammate never carries two icons.
 *
 * <p><b>Screen-space, exactly like Xaero.</b> Xaero's marker is not a quad floating in the 3D scene:
 * its {@code MinimapElementWorldRendererHandler} takes the marker's world position, multiplies it by
 * the camera and projection matrices to find where it lands on screen, converts that to pixels, and
 * draws the icon flat at a fixed <em>pixel</em> size. This renderer does the same, as a HUD element.
 *
 * <p>That is what produces the zoom behaviour: because the icon's size is in screen pixels and never
 * passes through the projection, zooming in magnifies the world <em>past</em> a marker that stays the
 * same pixel size — so markers read as getting smaller relative to everything around them. Drawing
 * the icon as a world-space billboard cannot reproduce this, whether it is given a fixed world size
 * (zoom magnifies it too) or a distance-compensated one (zoom does nothing to it at all).
 *
 * <p>Drawing in screen space also disposes of every depth and layer problem a world-space billboard
 * has: the icon is composited over the finished 3D frame, so nothing can punch through it, and the
 * face is drawn with vanilla's own {@link PlayerSkinDrawer} rather than hand-built skin quads.
 *
 * <p>The marker idles slightly transparent and, when the crosshair lands on it, snaps to full opacity
 * with the teammate's name popped out beside it — Xaero's hover behaviour. The one deliberate
 * difference is the ask: Xaero's border is white, here it is the teammate's ping colour, so the
 * marker is colour-coded to match their pings and HUD row.
 */
@Environment(EnvType.CLIENT)
public final class TeammateIconRenderer implements HudElement {
    public static final Identifier ID = Identifier.of(
            dev.spog.teamlocator.TeamLocatorConstants.MOD_ID, "teammate_icons");

    /**
     * Fallback face size in screen pixels, used only if the configured value is somehow unusable.
     * The real size comes from {@code playerMarkerSize}, driven by the Player Marker slider.
     */
    private static final int DEFAULT_FACE_SIZE = 5;
    /** Thickness of the ping-colour frame around the face, in screen pixels. */
    private static final int BORDER = 1;

    /**
     * Feet-to-body-centre lift, so the icon floats at the teammate's chest rather than at their feet.
     * This is the same 0.9 the Xaero-path mixin applies to Xaero's own icon.
     */
    private static final double BODY_CENTRE_OFFSET = 0.9;

    /**
     * How far past the hide distance the marker takes to reach full opacity. Fixed rather than
     * configurable: it exists so a marker fades in instead of popping, which is a rendering nicety
     * rather than a preference. The hide distance itself is the Hide Within slider.
     */
    private static final double FADE_LENGTH = 10.0;

    /** Name plate background: black at ~90/255 alpha, as Xaero draws it. */
    private static final int NAME_BG_ALPHA = 90;
    /**
     * Overlap, in unscaled pixels, between the icon's edge and a hover label's plate. Small and
     * negative-facing: the plate stays touching the marker rather than floating away from it, and the
     * icon is drawn afterwards so it covers the tucked-under sliver.
     */
    private static final int NAME_GAP = 2;
    /**
     * Padding inside a label plate on the side FACING the icon, in scaled pixels. Larger than
     * {@link #NAME_PAD_OUTER} so the text is pushed clear of the marker while the plate itself stays
     * attached to it — the box grows to absorb the offset rather than the box moving away.
     */
    private static final int NAME_PAD_INNER = 6;
    /** Padding on the label's outer side, away from the icon. */
    private static final int NAME_PAD_OUTER = 2;
    /**
     * The hover name's size relative to the icon. The font is 8px tall at scale 1, so tying the text
     * to the marker's size keeps the two proportional at any slider setting; the divisor makes the
     * name a little smaller than the icon rather than matching it.
     */
    private static final float NAME_SCALE_PER_PIXEL = 1.0f / 9.0f;
    /** Never shrink the name past this, or it stops being legible at the smallest marker sizes. */
    private static final float NAME_SCALE_MIN = 0.5f;

    /**
     * How close to the crosshair an icon must be to count as "looked at", as the cosine of the angle
     * between the look vector and the direction to the icon: 1&deg; to highlight, releasing at
     * ~1.4&deg;. The two thresholds give hysteresis, so an icon at the edge of the cone doesn't
     * strobe between dim and highlighted.
     */
    private static final double SHOW_COS = Math.cos(Math.toRadians(1.0));
    private static final double HIDE_COS = Math.cos(Math.toRadians(1.4));

    /**
     * Per-teammate "currently looked at" latch backing the hysteresis. Pruned each frame against the
     * live teammate list, so it cannot grow unbounded.
     */
    private static final java.util.Map<UUID, Boolean> shown =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Scratch vector for the world→clip projection; reused so the render loop allocates nothing. */
    private final Vector4f projected = new Vector4f();

    public TeammateIconRenderer() {
    }

    @Override
    public void render(DrawContext graphics, RenderTickCounter delta) {
        // The master switch turns markers off entirely, whichever renderer would draw them.
        if (!TeamLocatorClient.CONFIG.playerMarkersEnabled) {
            return;
        }
        // Off entirely when the player handed in-world icons to Xaero AND Xaero is present to take
        // them; otherwise the mod always draws its own. Exactly one renderer ever runs.
        if (TeamLocatorClient.CONFIG.useXaeroInWorldIcons && XaeroCompat.isMinimapInstalled()) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.options.hudHidden) {
            return;
        }
        Identifier dimension = mc.player.getEntityWorld().getRegistryKey().getValue();
        List<TrackedPos> teammates = ClientState.latest();
        if (teammates.isEmpty()) {
            return;
        }

        Camera camera = mc.gameRenderer.getCamera();
        Vec3d cam = camera.getCameraPos();
        // The partial tick the CAMERA was placed at — not the raw frame partial. The icon's screen
        // position is the difference between the teammate's interpolated position and the camera's,
        // so both must be sampled at the same instant; mixing two different sub-tick moments made the
        // marker oscillate by a fraction of a block every frame, which is the jitter under movement.
        float partial = camera.getLastTickProgress();

        // The camera+projection transform for this frame, so a world point can be projected to the
        // screen exactly where the 3D scene put it.
        Matrix4f worldToClip = worldToClip(mc, camera);
        if (worldToClip == null) {
            return;
        }

        org.joml.Vector3fc fwd = camera.getHorizontalPlane();
        Vec3d look = new Vec3d(fwd.x(), fwd.y(), fwd.z());

        // Forget latch state for teammates no longer tracked, so the map can't grow unbounded.
        java.util.Set<UUID> live = new java.util.HashSet<>();
        for (TrackedPos teammate : teammates) {
            live.add(teammate.id());
        }
        shown.keySet().retainAll(live);

        for (TrackedPos teammate : teammates) {
            // Positions never cross dimensions, so an entry in another dimension is stale for this
            // view — skip it rather than draw a marker at coordinates in a different world.
            if (!teammate.dimension().equals(dimension)) {
                continue;
            }
            renderIcon(mc, graphics, cam, worldToClip, look, teammate, partial);
        }
    }

    /**
     * The frame's world→clip matrix: the perspective projection times the camera's view rotation. A
     * world point made relative to the camera and multiplied by this lands in clip space, which
     * divides down to normalised device coordinates and then to pixels — the same chain Xaero's
     * handler runs.
     *
     * <p>Built from the game renderer's own projection for the current FOV, so a zoom (spyglass, a
     * zoom mod that drives FOV) is reflected: the marker's projected POSITION tracks the zoomed world
     * exactly, while its pixel SIZE does not.
     */
    private static Matrix4f worldToClip(MinecraftClient mc, Camera camera) {
        Matrix4f projection = mc.gameRenderer.getBasicProjectionMatrix(
                mc.options.getFov().getValue().floatValue());
        if (projection == null) {
            return null;
        }
        // The view rotation is the camera's orientation inverted: the world is rotated opposite the
        // way the camera faces.
        Matrix4f view = new Matrix4f().rotation(
                camera.getRotation().conjugate(new org.joml.Quaternionf()));
        return projection.mul(view, new Matrix4f());
    }

    private void renderIcon(MinecraftClient mc, DrawContext graphics, Vec3d cam,
                            Matrix4f worldToClip, Vec3d look, TrackedPos teammate, float partial) {
        // Prefer the teammate's live entity when it is loaded locally: vanilla interpolates it every
        // frame with the partial tick, so the icon locks onto a moving player smoothly. The relay
        // position is only a fallback for teammates out of entity range, where its ~200ms lag is
        // invisible anyway.
        double px;
        double py;
        double pz;
        var entity = mc.world.getPlayerByUuid(teammate.id());
        if (entity != null) {
            // A player entity that just entered render range has not ticked yet (age == 0), so its
            // prevX/Y/Z are still zero; interpolating through that would fling the icon toward world
            // origin for a frame. Use the flat position until it has ticked.
            if (entity.age > 0) {
                Vec3d p = entity.getLerpedPos(partial);
                px = p.x;
                py = p.y;
                pz = p.z;
            } else {
                px = entity.getX();
                py = entity.getY();
                pz = entity.getZ();
            }
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

        int fade = fadeAlpha(distance);
        if (fade <= 0) {
            return; // inside the near-fade: too close to be worth a locator, exactly like Xaero
        }

        // Behind the camera: the projection would wrap it round to a bogus on-screen point, so cull it
        // the way Xaero does — on the dot product of the look vector with the direction to the icon.
        if (look.x * dx + look.y * dy + look.z * dz <= 0.0) {
            return;
        }

        // World → clip → normalised device coords → pixels. This is the whole trick: the icon's
        // POSITION comes through the projection (so it tracks the world, and a zoom moves it exactly
        // as it moves the scene), while its SIZE below is in fixed screen pixels and never touches
        // the projection — which is why zoom makes markers read as smaller against the world.
        projected.set((float) dx, (float) dy, (float) dz, 1.0f);
        projected.mul(worldToClip);
        if (projected.w() <= 0.0f) {
            return;
        }
        float ndcX = projected.x() / projected.w();
        float ndcY = projected.y() / projected.w();
        // Kept as floats: the true, fractional screen position. The draw calls below take ints, so
        // the whole part positions them and the fraction is applied as a sub-pixel translate on the
        // pose — without that, rounding snapped the marker to whole pixels and smooth world motion
        // came out as a visible stair-step.
        float exactX = (1.0f + ndcX) / 2.0f * graphics.getScaledWindowWidth();
        float exactY = (1.0f - ndcY) / 2.0f * graphics.getScaledWindowHeight();
        int screenX = (int) Math.floor(exactX);
        int screenY = (int) Math.floor(exactY);
        float subX = exactX - screenX;
        float subY = exactY - screenY;

        // Xaero-style hover: an icon idles slightly transparent; putting the crosshair on it snaps it
        // to full opacity and pops the name out beside it. Hysteresis stops edge-of-cone flicker.
        boolean looked = passesCrosshairGate(teammate.id(), look, dx, dy, dz, distance);
        int idleAlpha = Math.clamp(
                Math.round(TeamLocatorClient.CONFIG.playerMarkerIdleOpacity * 255 / 100.0f), 0, 255);
        int alpha = looked ? fade : fade * idleAlpha / 255;
        if (alpha <= 0) {
            return;
        }

        int faceSize = Math.max(1, TeamLocatorClient.CONFIG.playerMarkerSize > 0
                ? TeamLocatorClient.CONFIG.playerMarkerSize : DEFAULT_FACE_SIZE);
        int half = faceSize / 2;
        int faceX = screenX - half;
        int faceY = screenY - half;

        // Everything for this marker is drawn inside a pose carrying the sub-pixel remainder, so the
        // icon sits at its true fractional position and slides smoothly instead of snapping.
        graphics.getMatrices().pushMatrix();
        graphics.getMatrices().translate(subX, subY);

        // 1) The labels FIRST, so the icon paints over them: each plate is tucked under the marker's
        //    edge, and drawing them before the frame and face is what puts them behind. The name goes
        //    on the left, the distance (when enabled) on the right. Only shown while looked at.
        if (looked) {
            drawSideLabel(mc, graphics, PingHandler.displayName(mc, teammate.id()),
                    faceX - BORDER, screenY, fade, faceSize, true);
            if (TeamLocatorClient.CONFIG.playerMarkerShowDistance) {
                drawSideLabel(mc, graphics, Math.round(distance) + "m",
                        faceX + faceSize + BORDER, screenY, fade, faceSize, false);
            }
        }

        // 2) The ping-colour frame: a filled rect one border wider than the face on every side. The
        //    face is painted over its middle, leaving the 1px ring Xaero draws (white; here the ping
        //    colour).
        int rgb = teammate.nameColor(0xFFFFFFFF) & 0xFFFFFF;
        graphics.fill(faceX - BORDER, faceY - BORDER,
                faceX + faceSize + BORDER, faceY + faceSize + BORDER, (alpha << 24) | rgb);

        // 3) The face, drawn by vanilla's own player-face helper — the same one the mod's HUD rows
        //    use — so the skin is sampled correctly with no hand-built quads or render layers. The
        //    trailing ARGB tint is what fades it: the no-tint overload hardcodes opaque white, which
        //    left the face solid while only the frame dimmed.
        PlayerSkinDrawer.draw(graphics, skin(mc, teammate.id()),
                faceX, faceY, faceSize, (alpha << 24) | 0xFFFFFF);

        graphics.getMatrices().popMatrix();
    }

    /**
     * Hidden inside the configured hide distance — where the teammate is close enough to simply look
     * at — then fading in over {@link #FADE_LENGTH} blocks so it appears rather than pops. A hide
     * distance of 0 disables the near-cull entirely and the marker is always drawn.
     */
    private static int fadeAlpha(double distance) {
        double hideWithin = TeamLocatorClient.CONFIG.playerMarkerHideDistance;
        if (hideWithin <= 0.0) {
            return 255;
        }
        if (distance <= hideWithin) {
            return 0;
        }
        double over = distance - hideWithin;
        if (over >= FADE_LENGTH) {
            return 255;
        }
        return (int) Math.round(255.0 * (over / FADE_LENGTH));
    }

    /**
     * True while the crosshair is over the icon. Dot-product gate with hysteresis: separate show and
     * hide angles latch the state so an icon sitting right at the edge of the cone doesn't strobe.
     */
    private static boolean passesCrosshairGate(UUID owner, Vec3d look,
                                               double dx, double dy, double dz, double distance) {
        if (look == null || distance < 1.0e-4) {
            return true; // no look vector (shouldn't happen) or icon on top of us: treat as looked at
        }
        double dot = (look.x * dx + look.y * dy + look.z * dz) / distance;
        boolean currentlyShown = shown.getOrDefault(owner, false);
        boolean nowShown = currentlyShown ? dot >= HIDE_COS : dot >= SHOW_COS;
        shown.put(owner, nowShown);
        return nowShown;
    }

    /**
     * One hover label: a translucent-black plate clear of the icon and vertically centred on it, with
     * the text on top — the Xaero-style popup shown only while the icon is looked at. {@code toLeft}
     * places it off the marker's left edge (the name) or its right (the distance); {@code anchorX} is
     * the corresponding icon edge in screen pixels.
     */
    private static void drawSideLabel(MinecraftClient mc, DrawContext graphics, String text,
                                      int anchorX, int centreY, int alpha, int faceSize,
                                      boolean toLeft) {
        TextRenderer font = mc.textRenderer;
        float scale = Math.max(NAME_SCALE_MIN, faceSize * NAME_SCALE_PER_PIXEL);
        // Asymmetric padding: wider on the side facing the icon. That offset is what moves the TEXT
        // away from the marker, and the plate is widened by the same amount so it still reaches back
        // to touch the icon — rather than the whole label detaching and floating off.
        int plateW = font.getWidth(text) + NAME_PAD_INNER + NAME_PAD_OUTER;
        int plateH = font.fontHeight;

        // CLAMPED TO THE MARKER. The pose is translated to the marker's edge in UNSCALED pixels and
        // only then scaled, so the label is rigidly attached to the icon and every coordinate below
        // is relative to that anchor. Placing the label by dividing the screen anchor by the scale and
        // rounding inside scaled space instead is an independent rounding that flips back and forth as
        // the marker slides sub-pixel — which makes the text shake against the icon under movement.
        graphics.getMatrices().pushMatrix();
        // The plate is anchored ON the icon's edge, overlapping it slightly so the two stay joined.
        graphics.getMatrices().translate(
                (float) (anchorX + (toLeft ? NAME_GAP : -NAME_GAP)), (float) centreY);
        graphics.getMatrices().scale(scale, scale);

        // Laid out around the origin: growing left of it for the name, right of it for the distance,
        // and vertically centred either way.
        int left = toLeft ? -plateW : 0;
        int top = -plateH / 2;
        int bgAlpha = Math.min(alpha, NAME_BG_ALPHA);
        graphics.fill(left, top, left + plateW, top + plateH, bgAlpha << 24);
        // Inset by the inner padding measured from whichever plate edge faces the icon, so the text
        // sits away from the marker on both sides.
        int textX = toLeft ? left + NAME_PAD_OUTER : left + NAME_PAD_INNER;
        graphics.drawText(font, text, textX, top, (alpha << 24) | 0xFFFFFF, false);
        graphics.getMatrices().popMatrix();
    }

    /**
     * The teammate's skin: the live entity's if it is loaded (so it matches what you see in the
     * world), else the one their {@link PlayerListEntry} carries on the connection, else the default
     * skin derived from their UUID while a real one loads.
     */
    private static SkinTextures skin(MinecraftClient mc, UUID id) {
        if (mc.world != null) {
            PlayerEntity entity = mc.world.getPlayerByUuid(id);
            if (entity instanceof AbstractClientPlayerEntity clientPlayer) {
                return clientPlayer.getSkin();
            }
        }
        if (mc.getNetworkHandler() != null) {
            PlayerListEntry info = mc.getNetworkHandler().getPlayerListEntry(id);
            if (info != null && info.getSkinTextures() != null) {
                return info.getSkinTextures();
            }
        }
        return DefaultSkinHelper.getSkinTextures(id);
    }
}
