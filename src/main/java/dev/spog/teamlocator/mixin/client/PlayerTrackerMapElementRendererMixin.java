package dev.spog.teamlocator.mixin.client;

import dev.spog.teamlocator.client.compat.xaero.XaeroWorldMapTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
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
 * <p>For our element, it skips the icon while the player's real entity is loaded locally, so Xaero's
 * native radar draws them there instead. This replaces Xaero's own {@code wasRenderedOnRadar} dedup,
 * whose per-frame confirm/reset handshake with the entity radar races our 5 Hz relay updates and
 * makes the icon flicker, with a deterministic per-frame entity check. Hovered ({@code highlighted})
 * elements are never deduped, so a hovered icon always falls through to Xaero's full
 * zoomed-and-labelled render instead of oscillating between highlighted and cancelled frames.
 *
 * <p>Unlike the minimap renderer, the world map has no IN_WORLD render location and no in-world icon
 * toggle, so this mixin is simpler: just the ownership check plus the hover-safe entity dedup.
 *
 * <p>{@code @Pseudo}: the target class only exists when Xaero's World Map is installed; the mixin is
 * silently skipped otherwise. {@code renderElement} always returns {@code false} natively (its return
 * value is not a handled/skip signal), so cancelling with {@code false} mirrors the renderer's own
 * return exactly and is safe.
 */
@Pseudo
@Mixin(targets = "xaero.map.radar.tracker.PlayerTrackerMapElementRenderer", remap = false)
public class PlayerTrackerMapElementRendererMixin {
    @Inject(
            method = "renderElement(Lxaero/map/radar/tracker/PlayerTrackerMapElement;ZDFDD"
                    + "Lxaero/map/element/render/ElementRenderInfo;"
                    + "Lxaero/map/element/MapElementGraphics;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"
                    + "Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void relay$filterRelayWorldMapElement(PlayerTrackerMapElement<?> element, boolean highlighted,
                                                  double optionalDepth, float partialTicks,
                                                  double cameraX, double cameraZ,
                                                  ElementRenderInfo renderInfo, MapElementGraphics graphics,
                                                  MultiBufferSource.BufferSource bufferSource,
                                                  MultiTextureRenderTypeRendererProvider rendererProvider,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (!(element.getSystem() instanceof XaeroWorldMapTracker)) {
            return;
        }
        // Never dedup a hovered element: a hovered icon must always render zoomed + labelled, so we
        // fall through here too, otherwise our cancel fights Xaero's re-highlight every frame.
        ClientLevel level = Minecraft.getInstance().level;
        if (!highlighted && level != null && level.getPlayerByUUID(element.getPlayerId()) != null) {
            cir.setReturnValue(false);
        }
    }
}
