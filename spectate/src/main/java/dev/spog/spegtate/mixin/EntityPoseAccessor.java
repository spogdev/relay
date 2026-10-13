package dev.spog.spegtate.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code Entity.DATA_POSE}, which is protected.
 *
 * <p>Needed to build a pose metadata update for a viewer. The target's own pose is never in its
 * inbound stream -- a client sets its pose locally and the server tells only the other clients --
 * so a viewer saw the target's sneak only once that echo came back round, a full round trip late.
 * Synthesising the update requires naming the data accessor, and the field is not public.
 *
 * <p>An accessor rather than a reflective lookup: this is remapped with the rest of the mixins, so
 * it cannot silently miss the way a hardcoded field name would.
 */
@Mixin(Entity.class)
public interface EntityPoseAccessor {
    @Accessor("DATA_POSE")
    static EntityDataAccessor<Pose> spegtate$pose() {
        throw new AssertionError();
    }
}
