package dev.spog.spegtate.capture;

import dev.spog.spegtate.SpectateClient;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Remembers the static setup packets the target's own server sent it, so a viewer joining later can
 * be given the same definitions.
 *
 * <p>This is the "replay" half of the session, and it is replay only in the sense that it is
 * recorded once and sent once. It carries no gameplay: registries, tags, enabled feature flags and
 * the login packet. The live stream that follows is forwarded as it arrives.
 *
 * <p>It matters because a viewer's own built-in registries are not necessarily the target server's.
 * A datapack that adds a block, a server on a different version of a registry, a modded biome -- all
 * of it would decode to the wrong ids, or fail to decode, if the viewer used its own copies. Taking
 * the definitions from the server the target is actually on is what makes the view correct rather
 * than approximately correct.
 *
 * <p>Recorded continuously from the moment the target connects, not when a session starts: by the
 * time a viewer asks, the configuration phase is long over and these packets will not be sent
 * again. Memory cost is a few hundred kilobytes for the lifetime of the connection.
 */
public final class HandshakeRecorder {
    /**
     * Packet kinds worth keeping. Ordering is preserved as received, because the client applies
     * them in order -- registry data before the login packet that references it, known packs before
     * the registry data they scope.
     */
    private static final List<Class<?>> RECORDED = List.of(
            ClientboundSelectKnownPacks.class,
            ClientboundRegistryDataPacket.class,
            ClientboundUpdateEnabledFeaturesPacket.class,
            ClientboundUpdateTagsPacket.class,
            ClientboundLoginPacket.class);

    /** One recorded packet, kept in its decoded form and re-encoded per session. */
    public record Recorded(Packet<?> packet) {
    }

    private final List<Recorded> packets = new ArrayList<>();

    /** True if this packet kind belongs in the handshake. */
    public static boolean isHandshake(Packet<?> packet) {
        return RECORDED.contains(packet.getClass());
    }

    /**
     * Records one packet if it is part of the handshake.
     *
     * <p>A second {@link ClientboundLoginPacket} replaces the first rather than appending: a server
     * transfer or a re-login re-sends it, and a viewer must be given one login, not a history of
     * them. Registry and tag packets append, because a server legitimately sends several (one per
     * registry).
     */
    public synchronized void offer(Packet<?> packet) {
        if (!isHandshake(packet)) {
            return;
        }
        if (packet instanceof ClientboundLoginPacket) {
            packets.removeIf(r -> r.packet() instanceof ClientboundLoginPacket);
        }
        if (packet instanceof ClientboundUpdateTagsPacket tags) {
            // Logged because a replay that carries the tag packet but not the item tags inside it
            // fails identically to one that carries no tag packet at all, and the two need
            // different fixes.
            SpectateClient.LOGGER.debug("spectate: recorded tags for {} registries, item tags: {}",
                    tags.tags().size(),
                    tags.tags().keySet().stream()
                            .anyMatch(k -> k.identifier().getPath().equals("item")));
        }
        packets.add(new Recorded(packet));
    }

    /** The recorded handshake in the order it was received. */
    public synchronized List<Recorded> snapshot() {
        return List.copyOf(packets);
    }

    /**
     * True once everything a viewer needs has been recorded.
     *
     * <p>All three parts are required, and checking only for the login packet was not enough: a
     * replay missing the tag packet produced a viewer that disconnected with dozens of
     * "Missing tag: 'minecraft:enchantable/...' in 'minecraft:item'" errors, because the
     * enchantment registry entries reference item tags and cannot be parsed without them. The
     * registry data has the same dependency the other way round, so neither is optional.
     *
     * <p>The usual reason for an incomplete recording is that the addon started recording after
     * the target's configuration phase had already passed -- it only sees packets from the moment
     * the mixin is live, and those packets are never sent again on an established connection. That
     * is a reconnect away from fixed, which {@link #describe()} is there to make diagnosable
     * rather than mysterious.
     */
    public synchronized boolean isComplete() {
        return hasLogin() && hasRegistries() && hasTags();
    }

    private boolean hasLogin() {
        return packets.stream().anyMatch(r -> r.packet() instanceof ClientboundLoginPacket);
    }

    private boolean hasRegistries() {
        return packets.stream().anyMatch(r -> r.packet() instanceof ClientboundRegistryDataPacket);
    }

    private boolean hasTags() {
        return packets.stream().anyMatch(r -> r.packet() instanceof ClientboundUpdateTagsPacket);
    }

    /** What was and was not recorded, for a log line when a session cannot start. */
    public synchronized String describe() {
        long registries = packets.stream()
                .filter(r -> r.packet() instanceof ClientboundRegistryDataPacket).count();
        return "login=" + hasLogin() + " registries=" + registries + " tags=" + hasTags()
                + " total=" + packets.size();
    }

    /** Clears the recording; called when the target's own connection drops. */
    public synchronized void clear() {
        if (!packets.isEmpty()) {
            SpectateClient.LOGGER.debug("spectate: cleared {} handshake packets", packets.size());
        }
        packets.clear();
    }
}
