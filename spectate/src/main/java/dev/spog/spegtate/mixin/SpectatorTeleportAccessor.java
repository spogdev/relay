package dev.spog.spegtate.mixin;

import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the uuid in the spectator-teleport packet, which is private with no getter.
 *
 * <p>The only public reader is {@code getTarget(ServerWorld)}, which resolves the entity against
 * a server world -- something a viewer does not have, since its "server" is this mod's loopback
 * host. The uuid itself is all that is needed; the camera resolves the entity from the viewer's
 * own world.
 *
 * <p>An accessor rather than reflection: it is remapped with the other mixins, so it cannot
 * silently miss on an obfuscated version the way a hardcoded field name would.
 *
 * <p>Two shapes of this were rejected by Mixin before this one, both only at class-load time:
 *
 * <ul>
 *   <li>An interface, which is how an accessor is usually written. A mixin's kind must match its
 *       target's and this packet is a class, so it failed at PREPARE with "target type
 *       mismatch".</li>
 *   <li>An abstract class carrying a {@code static} convenience method to do the cast. A mixin
 *       for a class may not declare a non-private static method -- it would have to be merged
 *       into the target -- so it failed at APPLY. That failure surfaced far from its cause: the
 *       packet class is first loaded while Fabric builds its vanilla packet table, so the error
 *       poisoned {@code VanillaPacketTypes} and every server connection died with an unrelated
 *       {@code NoClassDefFoundError}.</li>
 * </ul>
 *
 * <p>Hence the plain interface-free form below: instance accessor only, and the caller casts.
 */
@Mixin(net.minecraft.network.packet.c2s.play.SpectatorTeleportC2SPacket.class)
public abstract class SpectatorTeleportAccessor {
    @Accessor("targetUuid")
    public abstract UUID spegtate$uuid();
}
