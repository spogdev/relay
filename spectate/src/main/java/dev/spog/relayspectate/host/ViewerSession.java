package dev.spog.relayspectate.host;

import dev.spog.relayspectate.SpectateClient;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.network.Connection;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.UUID;

/**
 * The viewer's side of a spectate session: stands up a {@link LoopbackHost}, logs this client into
 * it, and feeds it the stream arriving from the target.
 *
 * <p>The login sequence mirrors what the game does for singleplayer, because that is the same
 * problem -- a client joining a server in its own process over an in-memory channel. Following the
 * vanilla sequence rather than inventing one is what lets the client's own world setup, chunk cache
 * and renderer come up normally:
 * <ol>
 *   <li>bind a loopback address and connect to it,</li>
 *   <li>attach a {@link ClientHandshakePacketListenerImpl} and initiate a play connection,</li>
 *   <li>send {@link ServerboundHelloPacket}.</li>
 * </ol>
 *
 * <p>From there this class answers as the server would, replaying the target's recorded handshake
 * and then its live packets.
 *
 * <p>Threading: {@link #start()} must run on the client thread (it touches {@code Minecraft}), and
 * the packet feed arrives from the relay socket's thread. The {@code Connection} it writes to is
 * thread-safe for sends -- Netty serializes them onto the channel's event loop.
 */
public final class ViewerSession {
    private final UUID target;
    private final LoopbackHost host = new LoopbackHost();

    private volatile Connection viewerSide;
    private volatile StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec;
    private volatile boolean closed;

    public ViewerSession(UUID target) {
        this.target = target;
    }

    public UUID target() {
        return target;
    }

    /**
     * Binds the host and starts this client's login against it.
     *
     * <p>Must be called on the client thread.
     */
    public void start() {
        Minecraft mc = Minecraft.getInstance();
        SocketAddress address = host.bind();
        Connection connection = host.connectViewer();
        this.viewerSide = connection;

        // Null ServerData and null parent screen: there is no real server behind this and no screen
        // to return to on failure -- the session's own teardown handles that. The duration is the
        // handshake's reported connect time, which is zero for a connection in this process.
        connection.initiateServerboundPlayConnection(
                address.toString(),
                0,
                new ClientHandshakePacketListenerImpl(
                        connection, mc, null, null, false, Duration.ZERO,
                        status -> SpectateClient.LOGGER.debug("spectate login: {}", status.getString()),
                        new LevelLoadTracker(), null));
        connection.send(new ServerboundHelloPacket(
                mc.getUser().getName(), mc.getUser().getProfileId()));
        SpectateClient.LOGGER.info("spectate: loopback session opened for {}", target);
    }

    /**
     * Hands one packet from the target to this client, as if its server had sent it.
     *
     * <p>The bytes are decoded against the registries the client received during the replayed
     * handshake, so ids resolve to the same definitions the target's server uses. Decoding failures
     * are dropped rather than propagated: a gap in the view is recoverable, a thrown exception on
     * the relay socket's thread is not.
     */
    public void feed(byte[] wire) {
        Connection conn = viewerSide;
        if (closed || conn == null || !conn.isConnected()) {
            return;
        }
        Connection serverSide = host.serverSide();
        if (serverSide == null) {
            return;
        }
        try {
            StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> c = codec;
            if (c == null) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.getConnection() == null) {
                    // The client has not finished login yet; the packet arrived early. Dropping is
                    // correct: the target re-sends chunks as the viewer's view distance is applied.
                    return;
                }
                c = dev.spog.relayspectate.capture.PacketWire.codec(
                        mc.getConnection().registryAccess());
                codec = c;
            }
            serverSide.send(dev.spog.relayspectate.capture.PacketWire.decode(c, wire));
        } catch (RuntimeException e) {
            SpectateClient.LOGGER.debug("spectate: dropped undecodable packet", e);
        }
    }

    /** True while the loopback client is attached. */
    public boolean isLive() {
        Connection conn = viewerSide;
        return !closed && conn != null && conn.isConnected();
    }

    /** Tears the session down. Idempotent, safe from any thread. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Connection conn = viewerSide;
        viewerSide = null;
        codec = null;
        if (conn != null && conn.isConnected()) {
            conn.disconnect(net.minecraft.network.chat.Component.literal("Spectate session ended"));
        }
        host.close();
        SpectateClient.LOGGER.info("spectate: session closed for {}", target);
    }
}
