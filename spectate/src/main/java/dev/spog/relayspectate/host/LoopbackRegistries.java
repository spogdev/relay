package dev.spog.relayspectate.host;

import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.network.ClientConnection;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Holds the registries a loopback client assembled from a replayed handshake, keyed by connection.
 *
 * <p>The handover point between
 * {@link dev.spog.relayspectate.mixin.ClientPacketListenerRegistryMixin} and {@link HostDriver}.
 * See that mixin for why the registries cannot simply be read when needed: the public accessor
 * goes through the local player, which does not exist until the login packet the host is trying to
 * encode has already been received.
 *
 * <p>Keyed by connection rather than held as a single value because two sessions can exist at once
 * -- one the viewer opened and one it is still tearing down -- and crossing their registries would
 * decode a packet against the wrong server's tables. Weakly keyed so a finished session does not
 * pin either the connection or a full registry set in memory.
 */
public final class LoopbackRegistries {
    private static final Map<ClientConnection, DynamicRegistryManager> BY_CONNECTION =
            Collections.synchronizedMap(new WeakHashMap<>());

    private LoopbackRegistries() {
    }

    /** Records the registries a loopback client built. Called from the client thread. */
    public static void publish(ClientConnection connection, DynamicRegistryManager registries) {
        BY_CONNECTION.put(connection, registries);
    }

    /** The registries for a loopback connection, or null if its client has not built them yet. */
    public static DynamicRegistryManager of(ClientConnection connection) {
        return BY_CONNECTION.get(connection);
    }

    /** Forgets a connection's registries when its session ends. */
    public static void forget(ClientConnection connection) {
        if (connection != null) {
            BY_CONNECTION.remove(connection);
        }
    }
}
