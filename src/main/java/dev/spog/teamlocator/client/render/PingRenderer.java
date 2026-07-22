package dev.spog.teamlocator.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.UUID;

/**
 * Draws teammates' location pings in the world as billboarded markers: a coloured pin, the pinger's
 * name to its left and the distance to its right, and a one-shot coloured ripple when it lands.
 *
 * <p><b>PORT STATUS (1.21.11): STUBBED — needs a from-scratch in-world renderer.</b> On {@code main}
 * (MC 26.1) this was a plain Fabric level-render hook that:
 * <ul>
 *   <li>registered on {@code LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN} (a 26.1-only Fabric event),
 *       reading a {@code PoseStack} and {@code MultiBufferSource.BufferSource} off the render context
 *       and the {@code Camera} off {@code gameRenderer.getMainCamera()};</li>
 *   <li>built billboarded quads by hand against {@code RenderTypes.textSeeThrough(texture)} /
 *       {@code RenderTypes.textBackgroundSeeThrough()} render layers, filling vertices with
 *       {@code VertexConsumer.addVertex(pose, ...).setColor(...).setUv(...).setLight(...).setNormal(...)};</li>
 *   <li>drew labels with {@code Font.drawInBatch(..., Font.DisplayMode.SEE_THROUGH, ...)}.</li>
 * </ul>
 *
 * <p>None of that maps mechanically to 1.21.11: Fabric's world-render events were reworked
 * ({@code WorldRenderEvents} in package {@code ...rendering.v1.world}, no {@code AFTER_TRANSLUCENT_TERRAIN},
 * a {@code WorldRenderContext} that exposes {@code matrices()}/{@code consumers()}/{@code commandQueue()}
 * but no {@code camera()}), and MC 1.21.11's render pipeline overhaul removed the {@code RenderLayer}
 * text/nametag static factories this relied on. Yarn name equivalents where they exist:
 * {@code PoseStack}→{@code MatrixStack}, {@code MultiBufferSource}→{@code VertexConsumerProvider},
 * {@code Font}→{@code TextRenderer} ({@code drawInBatch}→{@code draw(...)},
 * {@code Font.DisplayMode}→{@code TextRenderer.TextLayerType}), {@code Vec3}→{@code Vec3d},
 * {@code RenderTypes}→{@code RenderLayer} — but the layer construction and the event wiring both need
 * a genuine reimplementation against 1.21.11's pipeline, not a rename, so it is deliberately left as
 * a no-op rather than guessed at and shipped broken.
 *
 * <p><b>What still works meanwhile:</b> the whole map-ping data path is intact — placing a ping
 * ({@code /} keybind), removing your own, the relay echo, {@link dev.spog.teamlocator.client.PingState}
 * bookkeeping, the arrival sound, and Xaero minimap/world-map markers (those go through Xaero, not
 * this class). Only the vanilla in-world billboard is missing. See {@code git show main:src/main/java/
 * dev/spog/teamlocator/client/render/PingRenderer.java} for the full original to port from.
 */
@Environment(EnvType.CLIENT)
public final class PingRenderer {
    private PingRenderer() {
    }

    /**
     * No-op until the in-world renderer is reimplemented for 1.21.11's render pipeline (see class
     * javadoc). Called once from the client entrypoint; safe to call — it simply registers nothing.
     */
    public static void register() {
        // Intentionally empty: no world-render hook is registered on 1.21.11 yet.
    }

    /**
     * Whether this ping's labels are currently showing (the crosshair is over it) — read by the ping
     * keybind so pressing it again while looking at your own ping removes it rather than placing
     * another.
     *
     * <p>With no renderer running there is no crosshair-hover latch to consult, so this always
     * reports {@code false}. Consequence while stubbed: the map-ping key always <em>places</em> a
     * ping and never removes an existing one by looking at it (removal via the relay still works
     * through other paths; a replacement ping simply overwrites the old one in
     * {@link dev.spog.teamlocator.client.PingState}). Restore the real latch when the renderer is
     * reimplemented.
     */
    public static boolean isHovered(UUID owner) {
        return false;
    }
}
