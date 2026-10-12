package dev.spog.spegtate.mixin;

import dev.spog.spegtate.SpectateManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ends a spectate session when the player leaves the world.
 *
 * <p>A spectate session is not a server connection, but it looks like one to the player: they are
 * in a world, and the pause menu's disconnect button is how anyone leaves a world. Without this
 * the button tore down the loopback connection while the session at the service stayed open, so
 * the target kept capturing and streaming to nobody until something else happened to end it.
 *
 * <p>{@code disconnectFromWorld} rather than the button's own handler: the handler is a lambda, and
 * {@code Button}'s {@code onPress} is final so it cannot be swapped out. This is the one method
 * every path to leaving a world goes through, the button included, so it also covers leaving by
 * any other route.
 */
@Mixin(Minecraft.class)
public abstract class SpectateDisconnectMixin {
    @Inject(method = "disconnectFromWorld(Lnet/minecraft/network/chat/Component;)V",
            at = @At("HEAD"))
    private void spegtate$endSession(net.minecraft.network.chat.Component reason,
                                          CallbackInfo ci) {
        SpectateManager manager = SpectateManager.get();
        if (manager.isViewing()) {
            // Before the teardown, not after: stopViewing tells the service to stop the capture,
            // and it needs the socket still up to do that.
            manager.stopViewing();
        }
    }
}
