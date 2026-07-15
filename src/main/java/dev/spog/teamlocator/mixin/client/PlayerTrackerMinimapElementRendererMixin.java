package dev.spog.teamlocator.mixin.client;

import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.compat.xaero.XaeroMinimapTracker;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.hud.minimap.element.render.MinimapElementGraphics;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.player.tracker.PlayerTrackerMinimapElement;

/**
 * Optional mixin into Xaero's Minimap tracked-player renderer, implementing the "In-World Icons"
 * toggle for relay teammates only. Xaero gates in-world rendering of tracked players solely behind
 * its global TRACKED_PLAYERS_IN_WORLD setting, shared by every tracker system — there is no
 * per-tracker hook — so this skips just our elements during the IN_WORLD pass, leaving the
 * minimap/world-map markers (and any other mod's tracked players) untouched.
 *
 * <p>{@code @Pseudo}: the target class only exists when Xaero's Minimap is installed; the mixin is
 * silently skipped otherwise. Cancelling with {@code false} mirrors the renderer's own "skipped"
 * return path (its radar-dedup early-out), so the world renderer handler neither runs hover
 * handling nor counts the element against its index limit.
 */
@Pseudo
@Mixin(targets = "xaero.hud.minimap.player.tracker.PlayerTrackerMinimapElementRenderer", remap = false)
public class PlayerTrackerMinimapElementRendererMixin {
    @Inject(
            method = "renderElement(Lxaero/hud/minimap/player/tracker/PlayerTrackerMinimapElement;ZZDFDD"
                    + "Lxaero/hud/minimap/element/render/MinimapElementRenderInfo;"
                    + "Lxaero/hud/minimap/element/render/MinimapElementGraphics;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void relay$suppressInWorldIcon(PlayerTrackerMinimapElement<?> element, boolean highlighted,
                                           boolean outOfBounds, double optionalDepth, float partialTicks,
                                           double cameraX, double cameraZ, MinimapElementRenderInfo renderInfo,
                                           MinimapElementGraphics graphics,
                                           MultiBufferSource.BufferSource bufferSource,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (renderInfo.location == MinimapElementRenderLocation.IN_WORLD
                && element.getSystem() instanceof XaeroMinimapTracker
                && !TeamLocatorClient.CONFIG.xaeroInWorldIcons) {
            cir.setReturnValue(false);
        }
    }
}
