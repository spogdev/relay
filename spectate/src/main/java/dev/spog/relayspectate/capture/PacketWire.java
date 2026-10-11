package dev.spog.relayspectate.capture;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;

/**
 * Encodes and decodes whole game packets using the game's own protocol codec.
 *
 * <p>This is what keeps the addon from having to know any packet's shape.
 * {@link ProtocolInfo#codec()} is a {@code StreamCodec<ByteBuf, Packet<? super T>>} covering every
 * clientbound play packet, including the packet id -- so a captured packet round-trips through
 * these two methods without this mod ever naming its fields. Chunk formats, entity data types and
 * the rest change between versions; none of that reaches this class.
 *
 * <p>The registry access matters: many packets (items, block entities, biomes) encode registry ids
 * resolved against the connection's registries. On the capture side that is the target's own
 * server-provided registries, and on the host side it is the set replayed from the target, so both
 * ends agree.
 */
public final class PacketWire {
    private PacketWire() {
    }

    /** The clientbound play codec bound to one connection's registries. */
    public static StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec(
            RegistryAccess registries) {
        ProtocolInfo<ClientGamePacketListener> info =
                GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                        RegistryFriendlyByteBuf.decorator(registries));
        return info.codec();
    }

    /** Serializes one packet to its wire bytes, id included. */
    public static byte[] encode(StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec,
                                Packet<? super ClientGamePacketListener> packet) {
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
    public static Packet<? super ClientGamePacketListener> decode(
            StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec, byte[] bytes) {
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try {
            return codec.decode(buf);
        } finally {
            buf.release();
        }
    }
}
