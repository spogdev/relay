package dev.spog.spegtate.mixin;

import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the uuid in the spectator-teleport packet, which is private with no getter.
 *
 * <p>The only public reader resolves the entity against a server world -- something a viewer does
 * not have, since its "server" is this mod's loopback host. The uuid itself is all that is
 * needed; the camera resolves the entity from the viewer's own world.
 *
 * <p>An accessor rather than reflection: it is remapped with the other mixins, so it cannot
 * silently miss on an obfuscated version the way a hardcoded field name would.
 *
 * <p>An abstract class rather than an interface, which is what an accessor would normally be.
 * Mixin matches the mixin's own kind to the target's: an interface mixin may only target an
 * interface, and this packet is a class, so declaring one failed at PREPARE with "target type
 * mismatch" -- before the game window opened, and invisibly to javac.
 */
@Mixin(net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket.class)
public abstract class SpectatorTeleportAccessor {
    @Accessor("uuid")
    public abstract UUID spegtate$uuid();

    /** Reads the uuid out of a packet instance. */
    public static UUID spegtate$uuid(net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket packet) {
        return ((SpectatorTeleportAccessor) (Object) packet).spegtate$uuid();
    }
}
