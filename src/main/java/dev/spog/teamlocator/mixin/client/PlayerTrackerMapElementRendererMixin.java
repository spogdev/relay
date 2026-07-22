package dev.spog.teamlocator.mixin.client;

import dev.spog.teamlocator.client.compat.xaero.XaeroWorldMapTracker;
import net.minecraft.client.render.VertexConsumerProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.element.MapElementGraphics;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.map.radar.tracker.PlayerTrackerMapElement;

/**
 * Optional mixin into Xaero's World Map tracked-player renderer — the fullscreen-map counterpart to
 * {@link PlayerTrackerMinimapElementRendererMixin} — acting on relay elements only (any other mod's
 * tracked players are untouched).
 *
 * <p><b>Why we pin {@code renderedOnRadar} to false.</b> Xaero's element collector, each frame,
 * looks up every loaded player entity and calls {@code setRenderedOnRadar(true)} on the matching
 * tracker element; {@code renderElement} then demotes any radar-flagged element to a faded,
 * hover-only marker (its {@code wasRenderedOnRadar()} branch skips the full scaled-and-labelled
 * draw). For a relay teammate that is two things we don't want:
 * <ul>
 *   <li>While the teammate <b>moves near the edge of loaded-entity range</b>, the entity streams in
 *       and out, so the flag toggles frame to frame and the icon flickers.</li>
 *   <li>While the teammate is <b>close</b> (entity always loaded), the flag is always set, so our
 *       icon is permanently demoted to hover-only — you see nothing on the map unless you hover
 *       exactly where they are.</li>
 * </ul>
 * The previous approach (cancel our element whenever the entity is loaded, so Xaero's native radar
 * draws it instead) made the moving-target flicker worse and left close teammates hover-only. The
 * fix is to always draw <em>our</em> icon: at render HEAD we clear the radar flag on our element,
 * which the collector set just before us this frame. Because we re-clear it every frame it can
 * never thrash, so both the flicker and the hover-only-when-close behaviour disappear, and the icon
 * shows at all zoom levels and distances. Non-relay elements are untouched, so other mods' radar
 * dedup is unaffected.
 *
 * <p>This is intentionally World-Map-only. The minimap keeps its existing entity dedup — its
 * in-world icon handling is being reworked separately.
 *
 * <p>{@code @Pseudo}: the target class only exists when Xaero's World Map is installed; the mixin is
 * silently skipped otherwise. We only mutate the element's flag and never cancel, so the renderer's
 * own return path is unchanged.
 */
@Pseudo
@Mixin(targets = "xaero.map.radar.tracker.PlayerTrackerMapElementRenderer", remap = false)
public class PlayerTrackerMapElementRendererMixin {
    @Inject(
            method = "renderElement(Lxaero/map/radar/tracker/PlayerTrackerMapElement;ZDFDD"
                    + "Lxaero/map/element/render/ElementRenderInfo;"
                    + "Lxaero/map/element/MapElementGraphics;"
                    + "Lnet/minecraft/class_4597$class_4598;"
                    + "Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;)Z",
            at = @At("HEAD"))
    private void relay$alwaysRenderRelayWorldMapElement(PlayerTrackerMapElement<?> element, boolean highlighted,
                                                        double optionalDepth, float partialTicks,
                                                        double cameraX, double cameraZ,
                                                        ElementRenderInfo renderInfo, MapElementGraphics graphics,
                                                        VertexConsumerProvider.Immediate bufferSource,
                                                        MultiTextureRenderTypeRendererProvider rendererProvider,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (!(element.getSystem() instanceof XaeroWorldMapTracker)) {
            return;
        }
        // Clear the radar flag the collector set for this element this frame, so Xaero draws our
        // full icon rather than the faded hover-only marker. Re-cleared every frame, so the loaded
        // entity streaming in/out can never make it flicker.
        element.setRenderedOnRadar(false);
    }
}
