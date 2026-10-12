package dev.spog.relayspectate.capture;

import dev.spog.relayspectate.SpectateClient;
import dev.spog.relayspectate.net.SpectateProtocol;
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

    /**
     * Where encoded frames go, told what kind of packet each one was.
     *
     * <p>The packet is passed alongside the bytes so the receiver can order frames by kind without
     * re-parsing them. Reading a packet id back out of the wire bytes would mean this mod keeping
     * its own copy of vanilla's id table, which is the one thing {@link PacketWire} exists to
     * avoid.
     */
    private final java.util.function.BiConsumer<Packet<?>, byte[]> sink;
    private volatile StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec;
    private volatile boolean handshakeSent;

    /** Set once at least one chunk has been forwarded, so the viewer can be let into the world. */
    private volatile boolean sentAnyChunk;

    /** Counts packets this session could not re-encode, so a burst reports once. */
    private volatile long encodeFailures;
    /** Packets the filter declined, for the one-line summary. */
    private volatile long filteredPackets;
    /** Packets actually forwarded, for the one-line summary. */
    private volatile long forwardedPackets;
    /** Chunk packets forwarded live, as opposed to swept, for the one-line summary. */
    private volatile long liveChunks;
    /** Chunk packets the radius filter rejected, for the one-line summary. */
    private volatile long chunksOutOfRange;

    public CaptureSession(java.util.function.BiConsumer<Packet<?>, byte[]> sink) {
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
     */
    public void offer(Packet<?> packet) {
        if (viewers.isEmpty()) {
            return;
        }
        // Bundles are unwrapped by CaptureHooks before they reach here, so a bundle arriving at
        // this point would be a programming error rather than something to forward -- it has no
        // wire form of its own and encoding it is not possible.
        if (packet instanceof net.minecraft.network.protocol.BundlePacket<?>) {
            SpectateClient.LOGGER.warn("spectate: a bundle reached the session unwrapped");
            return;
        }
        if (!CaptureFilter.isForwardable(packet)) {
            // Counted by class, and reported once. A packet kind that a viewer turns out to need
            // is otherwise invisible here: dropping it is the normal case for most of the stream,
            // so there is nothing to warn about, and the only symptom is something missing in the
            // viewer's world with no record of what. Bundled packets were dropped this way for
            // the whole of this feature's development.
            long n = ++filteredPackets;
            if (n == 1 || n % 10000 == 0) {
                SpectateClient.LOGGER.debug("spectate: {} packet(s) not forwarded, latest {}",
                        n, packet.getClass().getSimpleName());
            }
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener listener = mc.getConnection();
        if (listener == null) {
            return;
        }
        if (!CaptureFilter.isWithinRadius(packet, localChunk(mc))) {
            // Counted separately from the filter's other rejections. A chunk dropped for distance
            // is the one rejection that is expected to be rare and that a viewer notices
            // immediately as terrain that never appears, so it needs to be distinguishable from
            // the ordinary filtered traffic in the summary.
            if (CaptureFilter.isChunkPacket(packet)) {
                chunksOutOfRange++;
            }
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
            sink.accept(packet, PacketWire.encode(c, game));
            forwardedPackets++;
            if (CaptureFilter.isChunkPacket(packet)) {
                sentAnyChunk = true;
                liveChunks++;
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

    /** Forwarded, filtered and failed counts, for one summary line per session. */
    public String frameSummary() {
        return forwardedPackets + " forwarded, " + filteredPackets + " filtered, "
                + encodeFailures + " failed to encode, " + liveChunks + " live chunk(s), "
                + chunksOutOfRange + " chunk(s) out of range";
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
