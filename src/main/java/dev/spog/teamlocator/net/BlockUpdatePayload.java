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
 * C2S. The sender's block list. Uploaded so the server can drop attack pings whose sender the
 * recipient has blocked (anti-spam). Block enforcement is server-side so it cannot be bypassed.
 */
public record BlockUpdatePayload(Set<UUID> blocked) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlockUpdatePayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(TeamLocator.MOD_ID, "block_update"));

    public static final StreamCodec<ByteBuf, BlockUpdatePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.collection(HashSet::new, UUIDUtil.STREAM_CODEC), BlockUpdatePayload::blocked,
            BlockUpdatePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
