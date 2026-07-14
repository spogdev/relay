package dev.spog.teamlocator.net;

import dev.spog.teamlocator.TeamLocator;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S2C. The positions of every player who shares coordinates with the receiving viewer. Sent by the
 * server a few times a second. The receiver decides whether to show each entry's dimension by
 * comparing it to their own.
 */
public record PositionSnapshotPayload(List<PlayerPos> entries) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PositionSnapshotPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(TeamLocator.MOD_ID, "position_snapshot"));

    public static final StreamCodec<ByteBuf, PositionSnapshotPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.collection(ArrayList::new, PlayerPos.CODEC), PositionSnapshotPayload::entries,
            PositionSnapshotPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }

    /** A single tracked player's live position and dimension. */
    public record PlayerPos(UUID id, double x, double y, double z, Identifier dimension) {
        public static final StreamCodec<ByteBuf, PlayerPos> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, PlayerPos::id,
                ByteBufCodecs.DOUBLE, PlayerPos::x,
                ByteBufCodecs.DOUBLE, PlayerPos::y,
                ByteBufCodecs.DOUBLE, PlayerPos::z,
                Identifier.STREAM_CODEC, PlayerPos::dimension,
                PlayerPos::new);
    }
}
