package dev.spog.teamlocator.mixin.client;

import dev.spog.teamlocator.client.RelayChatRouter;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diverts prefixed chat to the relay before it can reach the Minecraft server.
 *
 * <p>Injected at the <b>head</b> of {@code sendChat} and cancelling, so the outgoing packet is never
 * constructed — not filtered, not cancelled downstream, never built. That ordering is the whole
 * point: a relay message is private to your trusted group, and a message intended for them that
 * leaks into public server chat is the one failure this feature must not have. Anything short of
 * cancelling before the packet exists leaves a window where it could still be sent.
 *
 * <p>{@code sendChat} is the single choke point every typed message passes through — the chat
 * screen, command blocks' chat fallback, and any mod that sends chat programmatically — so there is
 * no second path to cover.
 */
@Mixin(ClientPacketListener.class)
public abstract class ChatInterceptMixin {
    @Inject(method = "sendChat(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
    private void relay$divertPrefixedChat(String message, CallbackInfo ci) {
        if (RelayChatRouter.handleOutgoing(message)) {
            // Consumed by the relay: cancel so vanilla never builds or signs a packet for it.
            ci.cancel();
        }
    }
}
