package dev.spog.relayspectate.capture;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.network.state.NetworkState;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.listener.ClientConfigurationPacketListener;
import net.minecraft.network.state.ConfigurationStates;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.state.PlayStateFactories;

/**
 * Encodes and decodes whole packets using the game's own protocol codecs.
 *
 * <p>This is what keeps the addon from having to know any packet's shape.
 * {@link NetworkState#codec()} is a {@code PacketCodec<ByteBuf, Packet<? super T>>} covering every
 * packet of a phase, including the packet id -- so a captured packet round-trips without this mod
 * ever naming its fields. Chunk formats, entity data types and the rest change between versions;
 * none of that reaches this class.
 *
 * <p><b>There are two codecs, not one, and they are not interchangeable.</b> A session replays
 * configuration-phase packets (registries, tags, feature flags) and then streams game-phase
 * packets, and each phase has its own packet-id table. The configuration codec also takes a plain
 * {@code PacketByteBuf} while the game codec needs one decorated with the connection's
 * registries, because game packets encode registry ids and configuration packets are what
 * establish those registries in the first place.
 *
 * <p>Using the wrong one does not fail to compile -- generics erase to the same thing -- it fails
 * at runtime with an unhelpful error about an unknown packet type, which is why the two are
 * separate named methods here rather than one parameterised call.
 */
public final class PacketWire {
    private PacketWire() {
    }

    /** The clientbound game-phase codec, bound to one connection's registries. */
    public static PacketCodec<ByteBuf, Packet<? super ClientPlayPacketListener>> gameCodec(
            DynamicRegistryManager registries) {
        NetworkState<ClientPlayPacketListener> info =
                PlayStateFactories.S2C.bind(
                        RegistryByteBuf.makeFactory(registries));
        return info.codec();
    }

    /**
     * The clientbound configuration-phase codec.
     *
     * <p>Needs no registries: the configuration phase is where registries are sent, so its packets
     * cannot depend on them.
     */
    public static PacketCodec<ByteBuf, Packet<? super ClientConfigurationPacketListener>>
            configurationCodec() {
        return ConfigurationStates.S2C.codec();
    }

    /** Serializes one packet to its wire bytes, id included. */
    public static <T extends net.minecraft.network.listener.PacketListener> byte[] encode(
            PacketCodec<ByteBuf, Packet<? super T>> codec, Packet<? super T> packet) {
        ByteBuf buf = Unpooled.buffer();
        try {
            codec.encode(buf, packet);
            byte[] out = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), out);
            return out;
        } finally {
            buf.release();
        }
    }

    /** Reads one packet back from wire bytes produced by {@link #encode}. */
    public static <T extends net.minecraft.network.listener.PacketListener> Packet<? super T> decode(
            PacketCodec<ByteBuf, Packet<? super T>> codec, byte[] bytes) {
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try {
            return codec.decode(buf);
        } finally {
            buf.release();
        }
    }
}
