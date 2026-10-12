package dev.spog.spegtate.mixin;

import dev.spog.spegtate.capture.CaptureHooks;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the window in which inbound packets are applied, so their effects are not re-captured.
 *
 * <p>{@code ClientEffectCaptureMixin} hooks {@code ClientWorld.levelEvent} and
 * {@code playSeededSound} to catch effects the target's client produces for itself. Those are the
 * same methods the inbound handlers call: {@code handleLevelEvent} calls {@code levelEvent} and
 * {@code handleSoundEvent} calls {@code playSeededSound}. Without a way to tell the two apart,
 * every sound and level event the server sent would be captured as it was applied and forwarded a
 * second time, and a viewer would get each one twice -- once from the forwarded packet itself and
 * once from the capture.
 *
 * <p>{@code ClientConnection.tick} is the right boundary because it is where the connection
 * drains and applies its queued packets, on the client thread, which is also the thread local
 * effects are raised on -- so the two cannot be distinguished by thread and a marker is required.
 *
 * <p>On 26.x this targets {@code PacketProcessor.processQueuedPackets}, a dedicated class that
 * does not exist on this version; the connection's own tick is where that work happens here.
 * {@code MinecraftClient.runTasks} was the other candidate and is not usable: it is inherited
 * from {@code ThreadExecutor} rather than declared on {@code MinecraftClient}, and Mixin cannot
 * inject into a method a target class does not itself declare.
 *
 * <p>A thrown handler would skip a {@code RETURN} injection and leave the mark set, disabling
 * local capture for the rest of the session. Rather than rely on the exit injection alone, the
 * mark is also cleared at the start of the next pass, so a leak can last at most one pass.
 */
@Mixin(ClientConnection.class)
public abstract class InboundApplyMarkerMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void spegtate$beginApplying(CallbackInfo ci) {
        // Sets rather than increments, so a mark left behind by a handler that threw during the
        // previous pass is cleared by the next one instead of persisting for the session.
        CaptureHooks.beginApplyingInbound();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void spegtate$endApplying(CallbackInfo ci) {
        CaptureHooks.endApplyingInbound();
    }
}
