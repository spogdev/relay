package dev.spog.teamlocator.net;

import dev.spog.teamlocator.TeamLocator;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * S2C. Tells the client "flash this player red on the HUD" — they signalled they are under attack.
 */
public record PingBroadcastPayload(UUID attacker) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PingBroadcastPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(TeamLocator.MOD_ID, "ping_broadcast"));

    public static final StreamCodec<ByteBuf, PingBroadcastPayload> CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, PingBroadcastPayload::attacker,
            PingBroadcastPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
