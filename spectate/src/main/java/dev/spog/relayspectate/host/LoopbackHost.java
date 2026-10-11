package dev.spog.relayspectate.host;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.local.LocalAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.EventLoopGroupHolder;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A Minecraft server that exists only inside this process and only speaks the packets handed to it.
 *
 * <p>This is the piece that makes spectating-from-the-menu possible without reimplementing the
 * client's world setup. The alternative -- constructing a {@code ClientLevel} directly -- fails on
 * contact: the constructor requires a {@code ClientPacketListener}, which requires a
 * {@code Connection}, and the renderer, chunk cache and light engine all assume a live connection
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
 * <p>Threading: the bind happens on the caller's thread and blocks; everything after that runs on
 * the local event loop. {@link #close()} is safe from any thread.
 */
public final class LoopbackHost {
    /** Distinguishes concurrent hosts; a session is torn down and rebuilt on reconnect. */
    private static final AtomicLong COUNTER = new AtomicLong();

    private final LocalAddress address;
    private volatile Channel serverChannel;
    private volatile Connection serverSide;

    public LoopbackHost() {
        this.address = new LocalAddress("relay-spectate-" + COUNTER.incrementAndGet());
    }

    /**
     * Binds the loopback listener. The returned address is what the viewer's client connects to;
     * nothing is reachable from outside this process.
     */
    public SocketAddress bind() {
        EventLoopGroupHolder holder = EventLoopGroupHolder.local();
        ServerBootstrap boot = new ServerBootstrap()
                .group(holder.eventLoopGroup())
                .channel(holder.serverChannelCls())
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        // The server end of the pair. SERVERBOUND is the flow it *reads*: packets
                        // the viewer's client sends us. We write CLIENTBOUND packets back.
                        Connection conn = new Connection(PacketFlow.SERVERBOUND);
                        Connection.configureInMemoryPipeline(ch.pipeline(), PacketFlow.SERVERBOUND);
                        ch.pipeline().addLast("relay_spectate_host", new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                serverSide = null;
                            }
                        });
                        ch.pipeline().addLast("packet_handler", conn);
                        serverSide = conn;
                    }
                });
        this.serverChannel = boot.bind(address).syncUninterruptibly().channel();
        return address;
    }

    /** Opens the viewer-side half of the pair. The caller drives login on it as usual. */
    public Connection connectViewer() {
        return Connection.connectToLocalServer(address);
    }

    /** The server end, or null before a client has connected or after it dropped. */
    public Connection serverSide() {
        return serverSide;
    }

    /** True once a viewer client is attached and the channel is live. */
    public boolean isAttached() {
        Connection conn = serverSide;
        return conn != null && conn.isConnected();
    }

    /** Unbinds and drops the channel. Idempotent. */
    public void close() {
        Channel ch = serverChannel;
        serverChannel = null;
        serverSide = null;
        if (ch != null) {
            ch.close();
        }
    }
}
