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
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The viewer's side of a spectate session: collects the target's handshake, stands up a
 * {@link LoopbackHost}, logs this client into it, and feeds it the live stream.
 *
 * <p>The login sequence mirrors what the game does for singleplayer, because that is the same
 * problem -- a client joining a server in its own process over an in-memory channel. Following the
 * vanilla sequence rather than inventing one is what lets the client's own world setup, chunk cache
 * and renderer come up normally.
 *
 * <p>Lifecycle, which is driven by what arrives rather than by a timer:
 * <ol>
 *   <li>the session is created when the service says the target accepted,</li>
 *   <li>configuration packets accumulate as they arrive,</li>
 *   <li>the login packet marks the handshake complete and triggers {@link #start()},</li>
 *   <li>live packets flow, queued until the client reaches the game phase.</li>
 * </ol>
 *
 * <p>Threading: {@link #start()} runs on the client thread; the frame feed arrives on the socket's
 * receive thread. Sends are safe from either -- Netty serializes them onto the channel's event loop.
 */
public final class ViewerSession {
    /**
     * Cap on the pre-game queue. A full initial chunk batch at a 10-chunk radius is a few hundred
     * packets; this leaves room for that while refusing to grow without bound if the client never
     * finishes login (in which case the session is torn down anyway).
     */
    private static final int MAX_QUEUED = 4096;

    private final UUID target;
    private final LoopbackHost host = new LoopbackHost();
    private final ConcurrentLinkedQueue<byte[]> pending = new ConcurrentLinkedQueue<>();

    /** Handshake configuration packets, decoded as they arrive. */
    private final List<Packet<?>> handshake = new ArrayList<>();

    private volatile Connection viewerSide;
    private volatile HostDriver driver;
    private volatile StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> gameCodec;
    private volatile boolean closed;
    private volatile boolean started;
    private volatile int dropped;

    public ViewerSession(UUID target) {
        this.target = target;
    }

    public UUID target() {
        return target;
    }

    /**
     * Accepts one configuration-phase handshake packet.
     *
     * <p>Decoded immediately rather than kept as bytes so a malformed handshake is caught before
     * the session starts, when refusing is still clean.
     */
    public synchronized void acceptHandshakePacket(byte[] wire) {
        if (closed || started) {
            return;
        }
        try {
            StreamCodec<ByteBuf, Packet<? super ClientConfigurationPacketListener>> codec =
                    PacketWire.configurationCodec();
            handshake.add(PacketWire.decode(codec, wire));
        } catch (RuntimeException e) {
            SpectateClient.LOGGER.warn("spectate: undecodable handshake packet; session will fail",
                    e);
        }
    }

    /**
     * Accepts the target's login packet, completing the handshake, and starts the session.
     *
     * <p>Must run on the client thread: {@link #start()} touches {@code Minecraft}.
     *
     * <p>The login packet is decoded with the game codec against the registries the target's server
     * uses. Those have just arrived in the configuration packets above but have not been applied to
     * anything yet, so this client's own registries are used for the decode -- which is correct for
     * the login packet specifically, because its fields are dimension keys and primitives rather
     * than registry-id references into a server-specific table.
     */
    public synchronized void acceptLoginPacket(byte[] wire) {
        if (closed || started) {
            return;
        }
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        try {
            StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec =
                    PacketWire.gameCodec(listener != null
                            ? listener.registryAccess()
                            : net.minecraft.core.RegistryAccess.EMPTY);
            handshake.add(PacketWire.decode(codec, wire));
        } catch (RuntimeException e) {
            SpectateClient.LOGGER.warn("spectate: undecodable login packet; aborting session", e);
            close();
            return;
        }
        start();
    }

    /**
     * Binds the host, starts this client's login against it, and drives the handshake replay.
     *
     * <p>Must be called on the client thread.
     */
    private void start() {
        if (started) {
            return;
        }
        started = true;
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
        HostDriver hostDriver = new HostDriver(serverSide, List.copyOf(handshake), () -> {
            ClientPacketListener current = Minecraft.getInstance().getConnection();
            return current == null ? null : current.registryAccess();
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
     * Hands one live packet from the target to this client, as if its server had sent it.
     *
     * <p>Packets arriving before the client reaches the game phase are queued rather than dropped:
     * the target streams as soon as the service asks it to, while the viewer is still in login, and
     * the first chunk batch is not re-sent.
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

    /**
     * Re-sends one packet to this client through the loopback.
     *
     * <p>Decoded and immediately re-encoded by the host's own outbound codec rather than passed
     * through as bytes. That is a round trip that could in principle be avoided, but the alternative
     * means writing pre-encoded bytes into a Netty pipeline that expects packet objects, and the
     * cost is small next to the chunk deserialization the client is about to do anyway.
     */
    private void send(byte[] wire) {
        Connection serverSide = host.serverSide();
        if (serverSide == null || !serverSide.isConnected()) {
            return;
        }
        try {
            StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec = gameCodec;
            if (codec == null) {
                ClientPacketListener listener = Minecraft.getInstance().getConnection();
                if (listener == null) {
                    return;
                }
                codec = PacketWire.gameCodec(listener.registryAccess());
                gameCodec = codec;
            }
            serverSide.send(PacketWire.decode(codec, wire));
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
        gameCodec = null;
        driver = null;
        if (conn != null && conn.isConnected()) {
            conn.disconnect(Component.literal("Spectate session ended"));
        }
        host.close();
        SpectateClient.LOGGER.info("spectate: session closed for {}", target);
    }
}
