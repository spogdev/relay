package dev.spog.relayspectate.host;

import dev.spog.relayspectate.SpectateClient;
import dev.spog.relayspectate.capture.PacketWire;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The viewer's side of a spectate session: stands up a {@link LoopbackHost}, logs this client into
 * it, and feeds it the stream arriving from the target.
 *
 * <p>The login sequence mirrors what the game does for singleplayer, because that is the same
 * problem -- a client joining a server in its own process over an in-memory channel. Following the
 * vanilla sequence rather than inventing one is what lets the client's own world setup, chunk cache
 * and renderer come up normally.
 *
 * <p>Live packets that arrive before the client reaches the game phase are queued rather than
 * dropped. The window is short but not empty: the target starts streaming as soon as the relay
 * tells it to, while the viewer is still exchanging login and configuration. Dropping those would
 * lose the first chunk batch, and the target has no reason to send it again.
 *
 * <p>Threading: {@link #start()} must run on the client thread (it touches {@code Minecraft}); the
 * packet feed arrives on the relay socket's thread. Sends are safe from either -- Netty serializes
 * them onto the channel's event loop.
 */
public final class ViewerSession {
    /**
     * Cap on the pre-game queue. A full initial chunk batch at a 10-chunk radius is a few hundred
     * packets; this leaves room for that and for entity spawns, while refusing to grow without
     * bound if the client never finishes login (in which case the session is torn down anyway).
     */
    private static final int MAX_QUEUED = 4096;

    private final UUID target;
    private final LoopbackHost host = new LoopbackHost();
    private final ConcurrentLinkedQueue<byte[]> pending = new ConcurrentLinkedQueue<>();

    private volatile Connection viewerSide;
    private volatile HostDriver driver;
    private volatile StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec;
    private volatile boolean closed;
    private volatile int dropped;

    public ViewerSession(UUID target) {
        this.target = target;
    }

    public UUID target() {
        return target;
    }

    /**
     * Binds the host, starts this client's login against it, and drives the handshake replay.
     *
     * <p>Must be called on the client thread.
     *
     * @param handshake the target's recorded handshake, in the order it was received
     */
    public void start(List<Packet<?>> handshake) {
        Minecraft mc = Minecraft.getInstance();
        SocketAddress address = host.bind();
        Connection connection = host.connectViewer();
        if (connection == null) {
            SpectateClient.LOGGER.warn("spectate: loopback did not attach; aborting session");
            close();
            return;
        }
        this.viewerSide = connection;

        Connection serverSide = host.serverSide();
        if (serverSide == null) {
            SpectateClient.LOGGER.warn("spectate: loopback server end missing; aborting session");
            close();
            return;
        }

        // The host encodes game packets against the registries the client builds from the replayed
        // configuration, so the supplier reads them off the client's connection once it exists.
        HostDriver hostDriver = new HostDriver(serverSide, handshake, () -> {
            ClientPacketListener listener = Minecraft.getInstance().getConnection();
            return listener == null ? null : listener.registryAccess();
        });
        this.driver = hostDriver;
        hostDriver.begin();

        // Null ServerData and null parent screen: there is no real server behind this, and the
        // session's own teardown handles failure rather than returning to a screen.
        connection.initiateServerboundPlayConnection(
                address.toString(),
                0,
                new ClientHandshakePacketListenerImpl(
                        connection, mc, null, null, false, Duration.ZERO,
                        status -> SpectateClient.LOGGER.debug("spectate login: {}",
                                status.getString()),
                        new LevelLoadTracker(), null));
        connection.send(new ServerboundHelloPacket(
                mc.getUser().getName(), mc.getUser().getProfileId()));
        SpectateClient.LOGGER.info("spectate: loopback session opened for {}", target);
    }

    /**
     * Hands one packet from the target to this client, as if its server had sent it.
     *
     * <p>Decoding failures are dropped rather than propagated: a gap in the view is recoverable, a
     * thrown exception on the relay socket's thread is not.
     */
    public void feed(byte[] wire) {
        if (closed) {
            return;
        }
        HostDriver d = driver;
        if (d == null || !d.isInGame()) {
            if (pending.size() < MAX_QUEUED) {
                pending.add(wire);
            } else {
                dropped++;
            }
            return;
        }
        drainPending();
        send(wire);
    }

    /** Flushes packets queued while the client was still logging in. */
    private void drainPending() {
        byte[] queued;
        while ((queued = pending.poll()) != null) {
            send(queued);
        }
        if (dropped > 0) {
            SpectateClient.LOGGER.warn("spectate: dropped {} packets queued before game phase",
                    dropped);
            dropped = 0;
        }
    }

    private void send(byte[] wire) {
        Connection serverSide = host.serverSide();
        if (serverSide == null || !serverSide.isConnected()) {
            return;
        }
        try {
            StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> c = codec;
            if (c == null) {
                ClientPacketListener listener = Minecraft.getInstance().getConnection();
                if (listener == null) {
                    return;
                }
                c = PacketWire.codec(listener.registryAccess());
                codec = c;
            }
            serverSide.send(PacketWire.decode(c, wire));
        } catch (RuntimeException e) {
            SpectateClient.LOGGER.debug("spectate: dropped undecodable packet", e);
        }
    }

    /** True while the loopback client is attached. */
    public boolean isLive() {
        Connection conn = viewerSide;
        return !closed && conn != null && conn.isConnected();
    }

    /** True once the viewer's client has a world and is rendering the target's surroundings. */
    public boolean isInGame() {
        HostDriver d = driver;
        return d != null && d.isInGame();
    }

    /** Tears the session down. Idempotent, safe from any thread. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        pending.clear();
        Connection conn = viewerSide;
        viewerSide = null;
        codec = null;
        driver = null;
        if (conn != null && conn.isConnected()) {
            conn.disconnect(Component.literal("Spectate session ended"));
        }
        host.close();
        SpectateClient.LOGGER.info("spectate: session closed for {}", target);
    }
}
