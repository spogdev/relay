package dev.spog.relayspectate.host;

import dev.spog.relayspectate.SpectateClient;
import dev.spog.relayspectate.capture.PacketWire;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.core.RegistryAccess;
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

    /** The target's login packet, still encoded; see {@link #acceptLoginPacket}. */
    private volatile byte[] loginWire;

    private volatile Connection viewerSide;
    private volatile HostDriver driver;
    private volatile StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> gameCodec;
    private volatile boolean closed;
    private final SpectateCamera camera;

    private volatile boolean started;
    private volatile boolean sentAnyPacket;

    /**
     * The target's position, held here until a {@link HostDriver} exists to receive it.
     *
     * <p>Needed because of a thread-ordering detail that is easy to miss. The target sends its
     * position before its terrain, and the service forwards frames in order -- but the login frame
     * is deferred to the client thread to create the driver, while a position frame is handled
     * inline on the socket thread. So the first position reliably arrives while {@code driver} is
     * still null. Dropping it there left the chunk cache centred on 0,0 and every swept chunk was
     * rejected on arrival as out of view range.
     */
    private volatile boolean seenPosition;
    private volatile boolean hasEarlyPosition;
    private volatile double earlyX;
    private volatile double earlyY;
    private volatile double earlyZ;
    private volatile float earlyYaw;
    private volatile float earlyPitch;
    private volatile int chunksReceived;
    private volatile long decodeFailures;
    private volatile int dropped;

    public ViewerSession(UUID target) {
        this.target = target;
        this.camera = new SpectateCamera(target);
    }

    /**
     * Keeps the camera on the target. Called once per client tick while the session is live.
     *
     * <p>Per-tick rather than once at start: the target's entity arrives through the stream, so it
     * does not exist when the session opens, and it is replaced when the target respawns or
     * changes dimension.
     */
    public void tickCamera() {
        if (!closed && isInGame()) {
            camera.tick();
        }
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
            Packet<?> decoded = PacketWire.decode(codec, wire);
            handshake.add(decoded);
            SpectateClient.LOGGER.info("spectate: handshake packet {} ({}B)",
                    decoded.getClass().getSimpleName(), wire.length);
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
     * <p>Kept as raw bytes rather than decoded here. Its first field is a dimension-type
     * {@code Holder}, which resolves against the registries the target's server sent -- and those
     * do not exist yet: they have arrived as configuration packets but nothing has applied them.
     * Decoding now would have to use whatever registries this client happens to have, which from
     * the main menu is nothing at all, and a spectate session started from the menu is the whole
     * point of the feature. So the decode is deferred to the host's game-phase switch, by which
     * point the viewer's client has built exactly the right set.
     */
    public synchronized void acceptLoginPacket(byte[] wire) {
        if (closed || started) {
            return;
        }
        this.loginWire = wire;
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

        // The host encodes game packets against the registries the viewer's client assembled from
        // the replayed configuration. They are read from LoopbackRegistries rather than through
        // Minecraft.getConnection(), which reads mc.player.connection -- and the player does not
        // exist until the login packet the host is about to encode has been received.
        HostDriver hostDriver = new HostDriver(serverSide, List.copyOf(handshake), loginWire,
                () -> LoopbackRegistries.of(connection));
        // Drain the queue the moment the game phase opens. Without this the queued terrain was
        // never delivered: feed() only drains when a NEW packet arrives, and the initial sweep is
        // the last thing the target sends -- so the 361 chunks sat in the queue and the viewer
        // rendered an empty world while every stage of the log looked correct.
        hostDriver.onEnteredGame(this::drainPending);
        this.driver = hostDriver;
        if (hasEarlyPosition) {
            // Arrived before the driver existed; hand it over now so the chunk cache is centred
            // before any queued terrain is flushed.
            hostDriver.updateTargetPosition(earlyX, earlyY, earlyZ, earlyYaw, earlyPitch);
        }
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

    /**
     * Applies the target's reported position: moves the viewer's camera and, crucially, the chunk
     * cache's view centre.
     *
     * <p>The centre is what makes the forwarded terrain usable at all. {@code ClientChunkCache}
     * discards any chunk outside it, and it starts at 0,0, so until the first position arrives
     * every chunk from a target standing anywhere else is thrown away on receipt.
     */
    public void applyTargetPosition(double x, double y, double z, float yaw, float pitch,
                                    int entityId, float headYaw, boolean onGround) {
        if (closed) {
            return;
        }
        seenPosition = true;
        HostDriver d = driver;
        if (d == null) {
            // The driver does not exist yet; hold the position for start() to pass on.
            earlyX = x;
            earlyY = y;
            earlyZ = z;
            earlyYaw = yaw;
            earlyPitch = pitch;
            hasEarlyPosition = true;
            return;
        }
        // Deliberately not gated on isInGame(). The target reports its position before it sends
        // terrain -- which is the right order -- so the first report almost always arrives while
        // the viewer's client is still logging in. Dropping it here left the chunk cache centred
        // on 0,0, and every one of the several hundred swept chunks was then rejected on arrival
        // with "Ignoring chunk since it's not in the view range". HostDriver stores a position it
        // cannot apply yet and applies it when the game phase opens, so this must reach it.
        d.updateTargetPosition(x, y, z, yaw, pitch);
        // Move the target's own entity. Its movement is not in its inbound stream -- a client
        // moves itself and the server never tells it where it is -- so without this the target
        // stood still in the viewer's world while every other entity moved normally. Synthesized
        // from the position report, which already arrives five times a second for the chunk
        // cache's benefit.
        if (entityId >= 0) {
            d.moveTargetEntity(entityId, x, y, z, yaw, pitch, headYaw, onGround);
        }
        // The first position is also the point at which the viewer has both terrain and somewhere
        // to stand, so it is when the loading screen can be released.
        if (sentAnyPacket) {
            d.announceChunksLoaded();
        }
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
                // The same registries the host encodes against, taken from the same place. This
                // used to read Minecraft.getConnection().registryAccess(), which is a different
                // route to what is usually the same set -- but it resolves through mc.player, so
                // during login it can return the viewer's own stale registries or none, and the
                // codec is cached on the first call. A codec built from the wrong tables decodes
                // ids against the wrong registry and throws on nearly every packet.
                Connection viewer = viewerSide;
                RegistryAccess access =
                        viewer == null ? null : LoopbackRegistries.of(viewer);
                if (access == null) {
                    return;
                }
                codec = PacketWire.gameCodec(access);
                gameCodec = codec;
            }
            Packet<?> decoded = PacketWire.decode(codec, wire);
            // Counted and sampled rather than logged per packet: a sweep is hundreds of chunks,
            // and what matters is how many arrived and where the first one sat relative to the
            // centre the host set -- which is what distinguishes "not sent" from "sent and
            // rejected".
            if (decoded instanceof net.minecraft.network.protocol.game
                    .ClientboundLevelChunkWithLightPacket chunk) {
                if (chunksReceived == 0) {
                    SpectateClient.LOGGER.info("spectate: first chunk at {},{}",
                            chunk.getX(), chunk.getZ());
                }
                chunksReceived++;
            }
            serverSide.send(decoded);
            if (!sentAnyPacket) {
                sentAnyPacket = true;
                // Terrain has reached the client, so the loading screen can go. Done here rather
                // than on the next position report because the position now arrives before the
                // terrain, and waiting for another would stall the viewer for no reason.
                HostDriver d = driver;
                if (d != null) {
                    d.announceChunksLoaded();
                }
            }
        } catch (RuntimeException e) {
            // Logged at warn, not debug. At debug this was invisible by default, and 808 of 818
            // packets failing to decode produced no output at all -- the session looked like a
            // delivery problem for three rounds when it was a decode problem all along. Counted
            // so a burst reports once per hundred rather than once per packet.
            long n = ++decodeFailures;
            if (n == 1 || n % 100 == 0) {
                SpectateClient.LOGGER.warn("spectate: {} packet(s) failed to decode; latest", n, e);
            }
        }
    }

    /**
     * True once the target has reported a position.
     *
     * <p>Worth distinguishing from "no chunks": the target sends its position immediately before
     * its terrain, so a session with neither failed before the sweep, while a session with a
     * position but no terrain failed inside it.
     */
    public boolean hasSeenPosition() {
        return hasEarlyPosition || seenPosition;
    }

    /** How many chunk packets this session has delivered to the client. */
    public int chunksReceived() {
        return chunksReceived;
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

    /**
     * Tears the session down. Idempotent, safe from any thread.
     *
     * <p>Closing the loopback is not enough on its own: the client would be left holding a world
     * whose connection is gone, with no screen to fall back to, which is what a vanilla disconnect
     * exists to handle. So when this session is the client's current world, teardown goes through
     * {@code Minecraft.disconnect}, which unloads the level and puts a real screen up. That must
     * happen on the client thread, hence the schedule.
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        camera.release();
        pending.clear();
        Connection conn = viewerSide;
        viewerSide = null;
        gameCodec = null;
        boolean wasInGame = driver != null && driver.isInGame();
        driver = null;

        if (wasInGame) {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                // Only if the client is still in the session's world: the user may already have
                // left it, in which case tearing down again would drop a world we do not own.
                if (mc.level != null) {
                    // The title screen is the right destination because a session is only ever
                    // started from the menu -- see SpectateManager.beginViewing, which refuses one
                    // from a client already in a world. There is therefore nothing to return to.
                    mc.disconnect(new TitleScreen(), false);
                }
                finishClose(conn);
            });
            return;
        }
        finishClose(conn);
    }

    private void finishClose(Connection conn) {
        LoopbackRegistries.forget(conn);
        if (conn != null && conn.isConnected()) {
            conn.disconnect(Component.literal("Spectate session ended"));
        }
        host.close();
        SpectateClient.LOGGER.info("spectate: session closed for {}", target);
    }
}
