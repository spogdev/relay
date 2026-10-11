package dev.spog.relayspectate.mixin;

import dev.spog.relayspectate.SpectateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the viewer's player noclipped while spectating.
 *
 * <p>Needed because collision does not read the game mode a spectate session can control.
 * {@code Player.tick} sets {@code noPhysics = isSpectator()}, and {@code Player.isSpectator()}
 * resolves through {@code AbstractClientPlayer.gameMode()}, which is
 * {@code getPlayerInfo().getGameMode()} -- a lookup of the player's own <em>tab-list entry</em>,
 * returning null when there is none.
 *
 * <p>A viewer has no such entry and never will: it is not a player on the target's server, so it
 * cannot appear in the target's tab list, and the list forwarded to it is the target's. Supplying a
 * synthetic self entry works but is fragile -- the target's own list, its resends and its removes
 * all arrive on the same path and can replace or delete it -- and it makes a basic property of
 * spectating depend on a packet that is not really about collision at all.
 *
 * <p>So {@code noPhysics} is set here instead, after vanilla has computed it, for the one player
 * this mod is driving. {@code Minecraft.gameMode}'s mode is kept at SPECTATOR separately, which is
 * what governs rendering; this covers the half of spectating that reads the tab list.
 */
@Mixin(LocalPlayer.class)
public abstract class SpectateNoClipMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void relayspectate$noclip(CallbackInfo ci) {
        if (!SpectateManager.get().isViewing()) {
            return;
        }
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (self != Minecraft.getInstance().player) {
            return;
        }
        self.noPhysics = true;
    }
}
