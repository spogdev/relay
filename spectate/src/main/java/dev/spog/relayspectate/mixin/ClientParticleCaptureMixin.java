package dev.spog.relayspectate.mixin;

import dev.spog.relayspectate.capture.CaptureHooks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forwards the particles the target's own client invents.
 *
 * <p>Particles reach a client two ways, and only one of them is in the packet stream. A server
 * sends {@code ClientboundLevelParticlesPacket} and {@code ClientboundLevelEventPacket}, both of
 * which {@code CaptureFilter} already forwards, so those appear for a viewer without any help.
 *
 * <p>The rest are spawned by the client itself, locally, and never travel anywhere: the crack
 * particles under a block being mined, a sprinting player's footfalls, splashes, the smoke from an
 * extinguished torch. The client computes them from state it already has, so no server ever
 * mentions them -- which means a viewer, whose client is being fed a packet stream rather than
 * simulating, has nothing to compute them from and shows none of them.
 *
 * <p>This is the same seam that hid the target's own movement, head rotation and arm swing: things
 * the target did itself are absent from the target's inbound stream. Here the fix is to notice the
 * client spawning a particle and turn it into the packet a server would have sent, which is then
 * filtered, encoded and forwarded by the ordinary capture path.
 *
 * <p>{@code doAddParticle} is the single private method both public {@code addParticle} overloads
 * funnel into, so one injection catches every local particle rather than needing one per overload.
 */
@Mixin(ClientLevel.class)
public abstract class ClientParticleCaptureMixin {
    @Inject(method = "doAddParticle", at = @At("HEAD"))
    private void relaySpectateCaptureParticle(ParticleOptions options, boolean overrideLimiter,
                                              boolean alwaysRender, double x, double y, double z,
                                              double dx, double dy, double dz, CallbackInfo ci) {
        // Guarded before building anything: this runs for every particle the client spawns, which
        // during mining or rain is a great many per tick, and the common case is that nobody is
        // watching. CaptureHooks.isStreaming is a volatile read.
        if (!CaptureHooks.isStreaming()) {
            return;
        }
        CaptureHooks.onLocalParticle(options, overrideLimiter, alwaysRender, x, y, z, dx, dy, dz);
    }
}
