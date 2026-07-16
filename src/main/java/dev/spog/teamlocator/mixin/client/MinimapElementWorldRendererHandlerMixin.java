package dev.spog.teamlocator.mixin.client;

import dev.spog.teamlocator.client.compat.xaero.XaeroMinimapTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import xaero.hud.minimap.element.render.MinimapElementReader;
import xaero.hud.minimap.player.tracker.PlayerTrackerMinimapElement;

/**
 * Optional mixin into Xaero's Minimap IN_WORLD element render handler, lifting the floating
 * in-world icon of relay elements from the tracked player's feet to their body centre. Any other
 * mod's tracked players — and every other element type this handler draws (waypoints etc.) — are
 * untouched.
 *
 * <p>Why here and not in our tracker's {@code ITrackedPlayerReader.getY()}: that value feeds
 * {@code PlayerTrackerMinimapElementReader.getRenderY}, which is shared by every render pass
 * (IN_MINIMAP / OVER_MINIMAP / IN_WORLD) plus any height-based map logic, so it must stay the true
 * feet position. The IN_WORLD pass is the only place the Y becomes a world-space render position,
 * and this handler is exclusively that pass: its {@code transformAndRenderForRenderer} ignores the
 * pre-computed Y argument handed down by the base handler and re-reads it through its single
 * {@code getRenderY} call before translating the world pose stack, so redirecting that one call
 * moves only the floating icon (and its hover box, which is projected from the same translation).
 * The tracker renderer's own later Y reads only feed its camera-distance fade, where 0.9 of a
 * block is irrelevant.
 *
 * <p>The offset is applied for our elements whether the Y came from the relay or from the live
 * entity once it loads locally ({@code getRenderY} switches to the interpolated entity position,
 * still feet-level), so the icon does not jump when a teammate crosses the local entity render
 * distance.
 *
 * <p>{@code @Pseudo}: the target class only exists when Xaero's Minimap is installed; the mixin is
 * silently skipped otherwise.
 */
@Pseudo
@Mixin(targets = "xaero.hud.minimap.element.render.world.MinimapElementWorldRendererHandler", remap = false)
public class MinimapElementWorldRendererHandlerMixin {
    /** Half the 1.8-block player height: feet Y + 0.9 = centre of the body. */
    private static final double RELAY_BODY_CENTRE_OFFSET = 0.9;

    @Redirect(
            method = "transformAndRenderForRenderer(Ljava/lang/Object;DDD"
                    + "Lxaero/hud/minimap/element/render/MinimapElementRenderer;Ljava/lang/Object;ID"
                    + "Lxaero/hud/minimap/element/render/MinimapElementRenderInfo;"
                    + "Lnet/minecraft/class_4597$class_4598;)Z",
            at = @At(value = "INVOKE",
                    target = "Lxaero/hud/minimap/element/render/MinimapElementReader;"
                            + "getRenderY(Ljava/lang/Object;Ljava/lang/Object;F)D"))
    private double relay$centreRelayInWorldIcon(MinimapElementReader<Object, Object> reader,
                                                Object element, Object context, float partialTicks) {
        double y = reader.getRenderY(element, context, partialTicks);
        if (element instanceof PlayerTrackerMinimapElement<?> trackerElement
                && trackerElement.getSystem() instanceof XaeroMinimapTracker) {
            y += RELAY_BODY_CENTRE_OFFSET;
        }
        return y;
    }
}
