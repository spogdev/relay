package dev.spog.teamlocator.client;

import dev.spog.teamlocator.mixin.client.BiomeManagerAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.util.Locale;

/**
 * Which server, of the several a network can run behind one address, this client is on -- so a
 * teammate in the hub is not drawn as a marker in lifesteal at the same coordinates.
 *
 * <p>The client is never told which backend a proxy put it on, so two things stand in for it:
 * <ul>
 *   <li>the address a transfer sent us to, for networks that move players between servers with
 *       transfers rather than a proxy -- each server then has an address of its own;</li>
 *   <li>the world's hashed seed, which every server sends when you join a world. Backends behind a
 *       proxy almost always have their own worlds, and so their own seeds, while shards of one world
 *       share its seed and so still count as one server.</li>
 * </ul>
 * A server that hides its seed sends the same value from every backend, which reads as one server
 * -- how everything behaved before this existed.
 */
@Environment(EnvType.CLIENT)
public final class WorldIdentity {
    /** Where the last transfer sent us, or null when the last connection was not a transfer. */
    private static volatile String transferredTo;

    private WorldIdentity() {
    }

    /** Every connection starts here; only a transfer carries an address of its own. */
    public static void connecting(ServerAddress address, TransferState transfer) {
        transferredTo = transfer == null ? null
                : address.getHost().toLowerCase(Locale.ROOT) + ":" + address.getPort();
    }

    /** This client's server and world, as sent with its position; null when not in a world. */
    public static String current(Minecraft mc) {
        if (mc.level == null) {
            return null;
        }
        long seed = ((BiomeManagerAccessor) mc.level.getBiomeManager()).relay$biomeZoomSeed();
        String address = transferredTo;
        return (address == null ? "" : address) + "/" + Long.toHexString(seed);
    }
}
