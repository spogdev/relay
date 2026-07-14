package dev.spog.teamlocator.net;

import dev.spog.teamlocator.TeamLocator;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S. "I'm being attacked." Empty marker; the server timestamps and fans it out to everyone who
 * mutually trusts the sender (and has not blocked them).
 */
public record PingPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PingPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(TeamLocator.MOD_ID, "ping"));

    public static final PingPayload INSTANCE = new PingPayload();

    public static final StreamCodec<ByteBuf, PingPayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
