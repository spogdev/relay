package dev.spog.teamlocator.net;

import dev.spog.teamlocator.TeamLocator;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * C2S. The sender's <em>effective outbound trust set</em>: every player the sender currently
 * shares coordinates with (active list, not hidden, sharing enabled). The server stores this as
 * "these viewers may see MY position" and routes position snapshots accordingly. A client's
 * private trust list therefore never leaves that client except as this inverted routing effect.
 */
public record TrustUpdatePayload(Set<UUID> sharingWith) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<TrustUpdatePayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(TeamLocator.MOD_ID, "trust_update"));

    public static final StreamCodec<ByteBuf, TrustUpdatePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.collection(HashSet::new, UUIDUtil.STREAM_CODEC), TrustUpdatePayload::sharingWith,
            TrustUpdatePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
