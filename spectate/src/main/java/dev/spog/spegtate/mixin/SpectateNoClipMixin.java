package dev.spog.spegtate.mixin;

import dev.spog.spegtate.SpectateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
 * <p>So {@code noPhysics} is set here instead, for the one player this mod is driving.
 * {@code Minecraft.gameMode}'s mode is kept at SPECTATOR separately, which is what governs
 * rendering; this covers the half of spectating that reads the tab list.
 *
 * <p>{@code isSpectator} is overridden rather than the flag assigned, because the order within a
 * tick defeats assignment. {@code Player.tick} assigns {@code noPhysics = isSpectator()} as its
 * first act and then calls up into {@code Avatar.tick}, where movement and collision happen.
 * Setting the flag at RETURN set it after every use that tick -- always exactly one tick stale,
 * which is indistinguishable from never working, and an earlier version of this did that and the
 * viewer still collided with everything. Setting it at HEAD is no better: vanilla's own assignment
 * is the next instruction and overwrites it.
 *
 * <p>Overriding the source fixes both, and fixes more than collision: every other
 * {@code isSpectator()} check on the viewer's player -- being pushed by entities, taking damage,
 * block interaction -- becomes correct at the same time, which is what spectating actually means.
 */
@Mixin(Player.class)
public abstract class SpectateNoClipMixin {
    @Inject(method = "isSpectator", at = @At("HEAD"), cancellable = true)
    private void spegtate$spectating(CallbackInfoReturnable<Boolean> cir) {
        if (!SpectateManager.get().isViewing()) {
            return;
        }
        if ((Player) (Object) this != Minecraft.getInstance().player) {
            return;
        }
        cir.setReturnValue(true);
    }
}
