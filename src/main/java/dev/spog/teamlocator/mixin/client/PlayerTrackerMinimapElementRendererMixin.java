package dev.spog.teamlocator.mixin.client;

import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.compat.xaero.XaeroMinimapTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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
 * Optional mixin into Xaero's Minimap tracked-player renderer, doing two jobs for relay elements
 * only (any other mod's tracked players are untouched):
 *
 * <ul>
 * <li>Map passes (IN_MINIMAP / OVER_MINIMAP / WORLD_MAP): skip our element while the player's real
 * entity is loaded locally — Xaero's native radar already draws them there. This replaces Xaero's
 * own {@code wasRenderedOnRadar} dedup, whose confirm/reset flag races our 5 Hz relay updates and
 * makes the icon flicker, with a deterministic per-frame check. Trade-off (same one Xaero's dedup
 * intends): a user who disabled Xaero's entity radar won't see nearby teammates on the maps.</li>
 * <li>IN_WORLD pass: implement the "In-World Icons" toggle. The floating in-world icon is drawn
 * only from tracker elements and is otherwise gated solely by Xaero's global
 * TRACKED_PLAYERS_IN_WORLD setting — there is no per-tracker hook — so nearby teammates keep
 * theirs (their element stays in the feed), and the toggle skips just ours.</li>
 * </ul>
 *
 * <p>{@code @Pseudo}: the target class only exists when Xaero's Minimap is installed; the mixin is
 * silently skipped otherwise. Cancelling with {@code false} mirrors the renderer's own "skipped"
 * return path (its radar-dedup early-out), so the renderer handler neither runs hover handling nor
 * counts the element against its index limit.
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
    private void relay$filterRelayElement(PlayerTrackerMinimapElement<?> element, boolean highlighted,
                                          boolean outOfBounds, double optionalDepth, float partialTicks,
                                          double cameraX, double cameraZ, MinimapElementRenderInfo renderInfo,
                                          MinimapElementGraphics graphics,
                                          MultiBufferSource.BufferSource bufferSource,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (!(element.getSystem() instanceof XaeroMinimapTracker)) {
            return;
        }
        if (renderInfo.location == MinimapElementRenderLocation.IN_WORLD) {
            if (!TeamLocatorClient.CONFIG.xaeroInWorldIcons) {
                cir.setReturnValue(false);
            }
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && level.getPlayerByUUID(element.getPlayerId()) != null) {
            cir.setReturnValue(false);
        }
    }
}
