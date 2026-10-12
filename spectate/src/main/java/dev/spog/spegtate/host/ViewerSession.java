package dev.spog.spegtate.host;

import dev.spog.spegtate.SpectateClient;
import dev.spog.spegtate.capture.PacketWire;
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
    /** Finds holes in the viewer's terrain and names them for the target to refill. */
    private final ChunkRepair repair = new ChunkRepair();
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
            enforceSpectator();
            camera.tick();
            // After camera.tick(), so the centre follows the camera entity this tick chose
            // rather than the previous one.
            HostDriver d = driver;
            if (d != null) {
                d.tickViewerCentre();
            }
        }
    }

    /**
     * Keeps the viewer's client in spectator, every tick, from its own state.
     *
     * <p>This does not depend on any packet, and that is the point. A viewer is never in the
     * target's tab list -- it is not on that server -- so the game mode a client reads from its own
     * tab-list entry is null for the whole session, and a spectate session cannot rely on entries
     * that will never exist. Rewriting the login and respawn packets fixed two ways of being
     * knocked out of spectator, but both were patches on a source of truth the viewer does not
     * have.
     *
     * <p>{@code MultiPlayerGameMode.localPlayerMode} is what actually decides it:
     * {@code Minecraft.gameMode.isSpectator()} reads it, and that governs no-clip and whether the
     * local player is drawn -- a viewer appearing as a body in third person was this being wrong.
     * Setting it directly each tick makes the viewer a spectator regardless of what arrives, and
     * nothing in the stream can take it away for longer than a tick.
     */
    private void enforceSpectator() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null && !mc.gameMode.isSpectator()) {
            mc.gameMode.setLocalMode(net.minecraft.world.level.GameType.SPECTATOR);
        }
    }

    public UUID target() {
        return target;
    }

    /**
     * Chunks this viewer is missing, for the manager to request. Empty most ticks.
     *
     * <p>Driven from the session rather than from {@link ChunkRepair} itself so the sweep only
     * runs while there is a live session with a position to centre on.
     */
    public java.util.List<net.minecraft.world.level.ChunkPos> missingChunks() {
        if (closed || !isInGame() || !seenPosition) {
            return java.util.List.of();
        }
        return repair.sweep(lastTargetX, lastTargetZ);
    }

    /**
     * Puts the viewer back on the target, for the Refocus keybind.
     *
     * <p>Needed because a free camera can be flown anywhere, including out of the streamed region
     * entirely, where there is no terrain and nothing to navigate by -- and the target is a player
     * who keeps moving, so flying back by hand is guesswork. Returning is one key.
     *
     * <p>Also re-centres the chunk cache immediately rather than waiting for the next tick, so the
     * terrain around the target is being accepted again by the time the viewer arrives.
     *
     * @return false if there is nothing to refocus on yet, so the caller can say so
     */
    public boolean refocus() {
        HostDriver d = driver;
        if (closed || d == null || !isInGame()) {
            return false;
        }
        if (!d.recentreOnTarget()) {
            return false;
        }
        d.tickViewerCentre();
        return true;
    }

    /**
     * Whether the chunk the viewer is being sent to actually has terrain.
     *
     * <p>Asked on refocus, because that is the one moment the viewer moves a long way at once. A
     * viewer that has flown away from the target has had its own chunk cache drop the terrain
     * behind it, so refocusing can land it in a world that is empty where it arrives -- and the
     * repair loop cannot fix that: it is rate limited and bounded to a handful of chunks per
     * sweep, deliberately, because a viewer missing hundreds of chunks is not a viewer with holes.
     * The answer to this one is the full sweep a session starts with.
     */
    public boolean targetChunkMissing() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !seenPosition) {
            return false;
        }
        int cx = net.minecraft.core.SectionPos.blockToSectionCoord(lastTargetX);
        int cz = net.minecraft.core.SectionPos.blockToSectionCoord(lastTargetZ);
        return mc.level.getChunk(cx, cz,
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) == null;
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
                                    int entityId, float headYaw, boolean onGround,
                                    int swingAction) {
        if (closed) {
            return;
        }
        seenPosition = true;
        // Kept for the chunk summary, which has to ask about where the target is now rather than
        // where it was when the session opened.
        lastTargetX = x;
        lastTargetZ = z;
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
        // from the position report, which arrives every tick for the chunk cache's benefit.
        if (entityId >= 0) {
            d.moveTargetEntity(entityId, x, y, z, yaw, pitch, headYaw, onGround);
            if (swingAction >= 0) {
                d.swingTargetArm(entityId, swingAction);
            }
        }
        // The first position is also the point at which the viewer has both terrain and somewhere
        // to stand, so it is when the loading screen can be released.
        if (sentAnyPacket) {
            d.announceChunksLoaded();
        }
    }

    /**
     * Forces the viewer's own entry in a forwarded tab list to spectator.
     *
     * <p>A client reads its own game mode from its tab-list entry:
     * {@code AbstractClientPlayer.gameMode()} is {@code getPlayerInfo().getGameMode()}, and
     * {@code Player.tick} then sets {@code noPhysics = isSpectator()} from it every tick. The
     * forwarded list is the target's, listing real game modes, so a viewer that is also on the
     * target's server received an entry saying survival -- which dropped it out of spectator and
     * restored collision.
     *
     * <p>The entry is corrected rather than removed. Removing it looks like the obvious fix and is
     * worse: {@code gameMode()} returns <em>null</em> when there is no entry, so
     * {@code isSpectator()} is then false and the viewer keeps colliding with everything. An
     * earlier version of this method did exactly that and caused the symptom it was meant to fix.
     *
     * <p>Rebuilt through the codec rather than by mutating the packet, which is immutable, and
     * only when the entry actually disagrees, so the ordinary case costs one scan of a short list.
     */
    private Packet<?> withOwnEntrySpectating(Packet<?> decoded) {
        // A respawn carries the target's game mode and the client applies it to itself through
        // setLocalMode -- which is where Minecraft.gameMode.isSpectator() reads from, and that is
        // what decides no-clip and whether the local player is rendered in third person. So the
        // target dying, respawning or changing dimension silently took the viewer out of
        // spectator: it collided with blocks again and appeared as a visible body behind its own
        // camera. The login packet is rewritten for the same reason; this is the same packet's
        // mid-session equivalent and needs the same treatment.
        if (decoded instanceof net.minecraft.network.protocol.game.ClientboundRespawnPacket re) {
            var info = re.commonPlayerSpawnInfo();
            if (info.gameType() != net.minecraft.world.level.GameType.SPECTATOR) {
                return new net.minecraft.network.protocol.game.ClientboundRespawnPacket(
                        new net.minecraft.network.protocol.game.CommonPlayerSpawnInfo(
                                info.dimensionType(),
                                info.dimension(),
                                info.seed(),
                                net.minecraft.world.level.GameType.SPECTATOR,
                                net.minecraft.world.level.GameType.SPECTATOR,
                                info.isDebug(),
                                info.isFlat(),
                                info.lastDeathLocation(),
                                info.portalCooldown(),
                                info.seaLevel()),
                        re.dataToKeep());
            }
            return decoded;
        }
        java.util.UUID me = Minecraft.getInstance().getUser().getProfileId();
        // A remove for the viewer's own id has to be dropped. The capture resends the tab list by
        // removing every player and re-adding them, which is the only way a client picks up a
        // changed profile -- but while the viewer's own entry is missing, gameMode() returns null,
        // isSpectator() is false and Player.tick turns collision back on. Other players' removes
        // pass through untouched.
        if (decoded instanceof net.minecraft.network.protocol.game
                .ClientboundPlayerInfoRemovePacket remove) {
            java.util.List<java.util.UUID> others = remove.profileIds().stream()
                    .filter(id -> !me.equals(id))
                    .toList();
            if (others.isEmpty()) {
                return null;
            }
            if (others.size() == remove.profileIds().size()) {
                return decoded;
            }
            return new net.minecraft.network.protocol.game
                    .ClientboundPlayerInfoRemovePacket(others);
        }
        if (!(decoded instanceof net.minecraft.network.protocol.game
                .ClientboundPlayerInfoUpdatePacket info)) {
            return decoded;
        }
        java.util.UUID self = me;
        boolean present = info.entries().stream()
                .anyMatch(e -> self.equals(e.profileId())
                        && e.gameMode() != net.minecraft.world.level.GameType.SPECTATOR);
        if (!present) {
            return decoded;
        }
        java.util.List<net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry>
                fixed = info.entries().stream()
                        .map(e -> self.equals(e.profileId()) ? spectating(e) : e)
                        .toList();
        var rebuilt = dev.spog.spegtate.capture.TabList.rebuild(
                info.actions(), fixed, Minecraft.getInstance());
        if (rebuilt != null) {
            return rebuilt;
        }
        // Forward the original rather than dropping it. Dropping an ADD_PLAYER loses every player
        // in it -- and this runs on the resend path, where the players have just been removed, so
        // dropping it leaves them removed and invisible. The viewer's own game mode is corrected
        // every tick from the login state anyway, so a stale entry here is a brief cosmetic
        // wrongness rather than a player that never comes back.
        SpectateClient.LOGGER.warn(
                "spectate: could not correct the viewer's tab-list entry; forwarding as sent");
        return decoded;
    }

    /** The same entry with its game mode forced to spectator. */
    private static net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry
            spectating(net.minecraft.network.protocol.game
                    .ClientboundPlayerInfoUpdatePacket.Entry entry) {
        return new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry(
                entry.profileId(),
                entry.profile(),
                entry.listed(),
                entry.latency(),
                net.minecraft.world.level.GameType.SPECTATOR,
                entry.displayName(),
                entry.showHat(),
                entry.listOrder(),
                entry.chatSession());
    }

    /** The target's most recent reported position; the repair sweep centres on it. */
    private volatile double lastTargetX;
    private volatile double lastTargetZ;

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
                chunksReceived++;
                // Before the packet is handed on, so a chunk that arrives and is then rejected by
                // the cache still counts as answered -- otherwise a chunk the target supplied but
                // the viewer discarded would be requested forever.
                repair.onChunkArrived(chunk.getX(), chunk.getZ());
            }
            Packet<?> forward = withOwnEntrySpectating(decoded);
            if (forward != null) {
                serverSide.send(forward);
            }
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
