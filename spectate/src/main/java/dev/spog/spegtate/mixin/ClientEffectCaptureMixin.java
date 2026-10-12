package dev.spog.spegtate.mixin;

import dev.spog.spegtate.capture.CaptureHooks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundCategory;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forwards the effects a server deliberately withholds from the player who caused them.
 *
 * <p>A server broadcasts a block break through
 * {@code ServerLevel.levelEvent(Entity except, ...)}, which ends at
 * {@code PlayerList.broadcast(PlayerEntity except, ...)} -- and the excluded player is the one who broke
 * the block. The breaker's own client predicts the break locally instead: {@code destroyBlock}
 * calls {@code World.setBlock} and {@code Block.destroy} itself, which produce the crack particles
 * and the break sound on that client alone.
 *
 * <p>So the target's inbound stream contains a block break's effects for every player except the
 * target. For the target's own breaking -- the thing a viewer is most likely to be watching -- it
 * contains nothing at all, and a viewer saw blocks vanish silently and without particles.
 *
 * <p>This is the same seam that hid the target's movement, head rotation and arm swing: a client
 * does not receive what it did itself. Here both missing effects travel together, since a block
 * break is a single level event carrying sound and particles, and each is turned into the packet
 * the server sent to everybody else.
 *
 * <p>Deliberately narrow. Ambient and weather particles are left to the viewer's own client, which
 * generates them from the streamed blocks and the forwarded weather state -- see the note in
 * {@code CaptureFilter}. These two hooks add only effects that are genuinely absent, which is why
 * they are on the level's event and sound entry points rather than on {@code addParticle}.
 */
@Mixin(ClientWorld.class)
public abstract class ClientEffectCaptureMixin {
    /**
     * Captures level events the client raises for itself.
     *
     * <p>{@code PARTICLES_DESTROY_BLOCK} is the one that matters -- block breaking -- but every
     * level event raised locally is in the same position, so none is singled out.
     */
    @Inject(method = "syncWorldEvent", at = @At("HEAD"))
    private void spegtateCaptureLevelEvent(Entity source, int type, BlockPos pos, int data,
                                                CallbackInfo ci) {
        if (!CaptureHooks.isStreaming()) {
            return;
        }
        CaptureHooks.onLocalLevelEvent(type, pos, data);
    }

    /**
     * Captures sounds the client plays for itself.
     *
     * <p>{@code playSeededSound} is where a client-predicted sound arrives, including the one from
     * a locally predicted block break. The seed is passed through so a viewer hears the same
     * variant of a sound with several, rather than a different random pick.
     */
    // Descriptor pinned, and taken verbatim from javap -s: ClientWorld has several
    // playSound overloads and an unpinned name would be ambiguous.
    @Inject(method = "playSound(Lnet/minecraft/entity/Entity;DDD"
            + "Lnet/minecraft/registry/entry/RegistryEntry;"
            + "Lnet/minecraft/sound/SoundCategory;FFJ)V", at = @At("HEAD"))
    private void spegtateCaptureSound(Entity source, double x, double y, double z,
                                           RegistryEntry<SoundEvent> sound, SoundCategory category,
                                           float volume, float pitch, long seed,
                                           CallbackInfo ci) {
        if (!CaptureHooks.isStreaming()) {
            return;
        }
        CaptureHooks.onLocalSound(sound, category, x, y, z, volume, pitch, seed);
    }
}
