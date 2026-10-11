package dev.spog.relayspectate.capture;

import dev.spog.relayspectate.SpectateClient;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The target's side of a spectate session: taps the packets this client receives from its own
 * server and forwards the relevant ones.
 *
 * <p>Only an allow-list of packet kinds is forwarded, and terrain is clipped to
 * {@link dev.spog.relayspectate.net.SpectateProtocol#CAPTURE_RADIUS_CHUNKS} around this player.
 * Both matter: the inbound stream carries things a viewer has no business seeing (this player's
 * inventory, private messages, advancement progress) and things a viewer cannot use (statistics,
 * recipe unlocks). Forwarding everything would be both a privacy leak and wasted bandwidth.
 *
 * <p>Filtering happens by packet class rather than by content, because the point is to decide
 * without parsing. A packet class not named in {@link CaptureFilter} is dropped -- the default is
 * to not send, so a packet kind added in a future version is withheld rather than leaked.
 */
public final class CaptureSession {
    /** Viewers currently being streamed to. */
    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();

    private final Consumer<byte[]> sink;
    private volatile StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec;
    private volatile boolean handshakeSent;

    /** Set once at least one chunk has been forwarded, so the viewer can be let into the world. */
    private volatile boolean sentAnyChunk;

    /** Counts packets this session could not re-encode, so a burst reports once. */
    private volatile long encodeFailures;

    public CaptureSession(Consumer<byte[]> sink) {
        this.sink = sink;
    }

    public void addViewer(UUID viewer) {
        viewers.add(viewer);
    }

    public void removeViewer(UUID viewer) {
        viewers.remove(viewer);
        if (viewers.isEmpty()) {
            handshakeSent = false;
            sentAnyChunk = false;
        }
    }

    public boolean hasViewers() {
        return !viewers.isEmpty();
    }

    public Set<UUID> viewers() {
        return Set.copyOf(viewers);
    }

    /** Drops all viewers, e.g. on disconnect or when sharing is revoked. */
    public void clear() {
        viewers.clear();
        handshakeSent = false;
        sentAnyChunk = false;
    }

    /**
     * Offers one inbound packet to the session. Called on the Netty event loop for every packet
     * this client receives, so it returns immediately when there is nothing to do -- that fast path
     * is the common case and must not cost the target anything measurable.
     *
     * <p>Also called on the client thread, for packets synthesized from things the target's client
     * did locally rather than received -- see {@code CaptureHooks.onLocalParticle}. That is safe:
     * the viewer set is concurrent, the codec field is volatile and idempotent to recompute, and
     * the sink serialises its own sends. Packet order between the two threads is not guaranteed,
     * which is fine for the packets involved -- a particle has no ordering relationship with the
     * terrain and entity stream.
     */
    public void offer(Packet<?> packet) {
        if (viewers.isEmpty()) {
            return;
        }
        if (!CaptureFilter.isForwardable(packet)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener listener = mc.getConnection();
        if (listener == null) {
            return;
        }
        if (!CaptureFilter.isWithinRadius(packet, localChunk(mc))) {
            return;
        }
        try {
            StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> c = codec;
            if (c == null) {
                c = PacketWire.gameCodec(listener.registryAccess());
                codec = c;
            }
            @SuppressWarnings("unchecked")
            Packet<? super ClientGamePacketListener> game =
                    (Packet<? super ClientGamePacketListener>) packet;
            sink.accept(PacketWire.encode(c, game));
            if (CaptureFilter.isChunkPacket(packet)) {
                sentAnyChunk = true;
            }
        } catch (RuntimeException e) {
            // A packet this mod cannot re-encode must not break the target's own game: drop it and
            // keep the session running. The viewer sees a gap rather than the target disconnecting.
            long n = ++encodeFailures;
            if (n == 1 || n % 100 == 0) {
                SpectateClient.LOGGER.warn("spectate: {} un-encodable packet(s), latest {}",
                        n, packet.getClass().getSimpleName(), e);
            }
        }
    }

    /** True once terrain has been sent, so the viewer has something to render. */
    public boolean hasSentChunks() {
        return sentAnyChunk;
    }

    /** This player's chunk, or null if there is no player yet. */
    private static ChunkPos localChunk(Minecraft mc) {
        Player player = mc.player;
        return player == null ? null : player.chunkPosition();
    }

    public boolean handshakeSent() {
        return handshakeSent;
    }

    public void markHandshakeSent() {
        handshakeSent = true;
    }

    /** Forgets the cached codec; the next packet rebuilds it against the current registries. */
    public void resetCodec() {
        codec = null;
    }
}
