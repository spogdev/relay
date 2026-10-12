package dev.spog.relayspectate.host;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.local.LocalAddress;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.NetworkingBackend;

import java.net.SocketAddress;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A MinecraftClient server that exists only inside this process and only speaks the packets handed to it.
 *
 * <p>This is the piece that makes spectating-from-the-menu possible without reimplementing the
 * client's world setup. The alternative -- constructing a {@code ClientWorld} directly -- fails on
 * contact: the constructor requires a {@code ClientPlayNetworkHandler}, which requires a
 * {@code ClientConnection}, and the renderer, chunk cache and light engine all assume a live connection
 * behind them. Rather than prying that apart, this takes the path the game already supports:
 * singleplayer runs the client against an in-memory Netty {@link io.netty.channel.local.LocalChannel}
 * with no socket underneath. So the viewer performs an ordinary login against a loopback address,
 * and every bit of world setup is done by vanilla code on the path vanilla already exercises.
 *
 * <p>What arrives from the target is forwarded verbatim in the game's own wire encoding, so this
 * class never parses a game packet. That is deliberate: packet shapes churn every version, and a
 * mod that re-derives them would need updating for each one. Here the only things that must match
 * the game version are the few class names referenced above.
 *
 * <p>Threading: {@link #bind()} and {@link #connectViewer()} block on the caller's thread;
 * everything after that runs on the local event loop. {@link #close()} is safe from any thread.
 */
public final class LoopbackHost {
    /** Distinguishes concurrent hosts; a session is torn down and rebuilt on reconnect. */
    private static final AtomicLong COUNTER = new AtomicLong();

    /**
     * Every connection belonging to a loopback session, so the capture tap can ignore them.
     *
     * <p>Needed because the viewer's end of the loopback reads {@code CLIENTBOUND}, exactly like a
     * connection to a real server -- {@code connectLocal} constructs it that way. Without
     * this the capture tap would treat the replayed handshake arriving over the loopback as if the
     * target's own server had sent it, overwriting the real recording and, if that client were also
     * being watched, forwarding a session's own stream back out through it.
     *
     * <p>Weakly keyed so a finished session's connections do not pin themselves in memory, and
     * synchronized because connections are registered on the client thread and read on Netty's.
     */
    private static final Set<ClientConnection> LOOPBACK =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** True if this connection is part of a spectate loopback rather than a real server. */
    public static boolean isLoopback(ClientConnection connection) {
        return LOOPBACK.contains(connection);
    }

    /**
     * How long {@link #connectViewer()} waits for the server end to be initialised. The two ends
     * attach on the same local event loop within microseconds; this bound exists so a failure
     * surfaces as a refused session rather than a hung client thread.
     */
    private static final long ATTACH_TIMEOUT_MS = 5_000L;

    private final LocalAddress address;

    /** Released once {@code initChannel} has published {@link #serverSide}. */
    private final CountDownLatch attached = new CountDownLatch(1);

    private volatile Channel serverChannel;
    private volatile ClientConnection serverSide;

    public LoopbackHost() {
        this.address = new LocalAddress("relay-spectate-" + COUNTER.incrementAndGet());
    }

    /**
     * Binds the loopback listener. The returned address is what the viewer's client connects to;
     * nothing is reachable from outside this process.
     */
    public SocketAddress bind() {
        NetworkingBackend holder = NetworkingBackend.local();
        ServerBootstrap boot = new ServerBootstrap()
                .group(holder.getEventLoopGroup())
                .channel(holder.getServerChannelClass())
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        // The server end of the pair. SERVERBOUND is the flow it *reads*: packets
                        // the viewer's client sends us. We write CLIENTBOUND packets back.
                        ClientConnection conn = new ClientConnection(NetworkSide.SERVERBOUND);
                        ClientConnection.addLocalValidator(ch.pipeline(), NetworkSide.SERVERBOUND);
                        ch.pipeline().addLast("relay_spectate_host",
                                new ChannelInboundHandlerAdapter() {
                                    @Override
                                    public void channelInactive(ChannelHandlerContext ctx) {
                                        serverSide = null;
                                    }
                                });
                        ch.pipeline().addLast("packet_handler", conn);
                        serverSide = conn;
                        LOOPBACK.add(conn);
                        attached.countDown();
                    }
                });
        this.serverChannel = boot.bind(address).syncUninterruptibly().channel();
        return address;
    }

    /**
     * Opens the viewer-side half of the pair and waits for the server end to exist.
     *
     * <p>The wait is the point. {@code connectLocal} syncs on the <em>client</em> channel's
     * connect future, which says nothing about whether the server side's {@code initChannel} has
     * run -- that happens independently on the local event loop. Returning without waiting left
     * {@link #serverSide()} legitimately null for the caller that immediately needs it, which would
     * have shown up as an intermittently failing session rather than anything reproducible.
     *
     * @return the viewer-side connection, or null if the server end never attached
     */
    public ClientConnection connectViewer() {
        ClientConnection client = ClientConnection.connectLocal(address);
        // Register before waiting: the client end reads CLIENTBOUND, so the capture tap must know
        // to skip it before the host starts replaying packets into it.
        LOOPBACK.add(client);
        try {
            if (!attached.await(ATTACH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return client;
    }

    /** The server end, or null before a client has connected or after it dropped. */
    public ClientConnection serverSide() {
        return serverSide;
    }

    /** True once a viewer client is attached and the channel is live. */
    public boolean isAttached() {
        ClientConnection conn = serverSide;
        return conn != null && conn.isOpen();
    }

    /** Unbinds and drops the channel. Idempotent. */
    public void close() {
        Channel ch = serverChannel;
        serverChannel = null;
        serverSide = null;
        attached.countDown();
        if (ch != null) {
            ch.close();
        }
    }
}
