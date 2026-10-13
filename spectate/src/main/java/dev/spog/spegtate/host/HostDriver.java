package dev.spog.spegtate.host;

import dev.spog.spegtate.SpectateClient;
import dev.spog.spegtate.capture.PacketWire;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.state.ConfigurationStates;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.state.PlayStateFactories;
import net.minecraft.network.state.LoginStates;
import net.minecraft.util.math.ChunkPos;

import java.util.List;
import java.util.function.Supplier;

/**
 * Walks the host's connection through handshake, login, configuration and into the game phase,
 * then puts the viewer in the target's world.
 *
 * <p>Each phase has its own packet-id table, and both ends must switch at the same point in the
 * stream. The order matters and is taken from what the vanilla server does, not inferred: outbound
 * is switched immediately after the packet that ends a phase, inbound when the client acknowledges.
 * Getting it wrong decodes the next packet against the wrong table, which surfaces as an opaque
 * decoder error naming neither the phase nor the cause.
 *
 * <p>Sequence, with who drives each step:
 * <ol>
 *   <li>client sends Intention -&gt; {@link HostHandshakeListener}; host switches to login</li>
 *   <li>client sends Hello -&gt; {@link HostLoginListener} answers LoginFinished</li>
 *   <li>client acknowledges -&gt; host switches to configuration</li>
 *   <li>{@link HostConfigurationListener} replays the target's registries, then FinishConfiguration</li>
 *   <li>client acknowledges -&gt; host switches to game and sends the join sequence</li>
 *   <li>live packets flow</li>
 * </ol>
 *
 * <p>The handshake phase is the one that is easy to skip, because a client connecting to a real
 * server is doing it before anything interesting happens. Skipping it does not degrade gracefully:
 * the intention packet's {@code handle} casts the listener, so a login listener in that slot throws
 * immediately.
 */
public final class HostDriver {
    private final ClientConnection connection;
    private final List<Packet<?>> handshake;
    private final byte[] loginWire;
    private final Supplier<DynamicRegistryManager> registries;

    /**
     * How many client-thread passes to wait for the registries. At roughly one per frame this is a
     * few seconds -- long enough to absorb a slow resource reload, short enough that a session that
     * will never be ready reports instead of hanging.
     */
    private static final int MAX_REGISTRY_WAIT_TICKS = 200;

    private volatile boolean inGame;
    private volatile boolean announcedChunksLoaded;

    /**
     * Whether the viewer has been placed in the world yet.
     *
     * <p>Only the first position report moves the viewer. See {@link #applyPosition}.
     */
    private volatile boolean sentInitialPosition;

    /**
     * How far behind the target the viewer starts, in blocks.
     *
     * <p>Far enough to see the target's whole model, close enough to stay well inside the streamed
     * radius.
     */
    private static final double SPAWN_OFFSET_BLOCKS = 4.0;
    private int registryWaitTicks;

    /**
     * Run once the game phase opens, so whatever queued up while the client was still logging in
     * can be flushed. Nothing else triggers that flush: the queue is drained by the arrival of a
     * later packet, and the initial terrain sweep is the last thing the target sends.
     */
    private volatile Runnable onEnteredGame = () -> { };

    /** The target's last reported position, applied as soon as the game phase is reached. */
    private volatile boolean hasPendingPosition;
    private volatile double pendingX;
    private volatile double pendingY;
    private volatile double pendingZ;
    private volatile float pendingYaw;
    private volatile float pendingPitch;

    /**
     * @param handshake the target's recorded configuration-phase packets, in order
     * @param loginWire the target's login packet, still encoded. It is decoded only once the
     *     client has built the target server's registries from {@code handshake}, because its
     *     first field is a dimension-type holder that resolves against them.
     * @param registries the registries the game-phase codec encodes against. These must be the set
     *     the <em>viewer's client</em> built from the replayed configuration, not the viewer's own
     *     server's: the ids in the target's packets resolve against the target server's registries,
     *     and the client has just been handed exactly those. Supplied lazily because they do not
     *     exist until the client finishes configuration.
     */
    public HostDriver(ClientConnection connection, List<Packet<?>> handshake, byte[] loginWire,
                      Supplier<DynamicRegistryManager> registries) {
        this.connection = connection;
        this.handshake = handshake;
        this.loginWire = loginWire;
        this.registries = registries;
    }

    /** Sets the callback run when the game phase opens. Called before {@link #begin()}. */
    public void onEnteredGame(Runnable callback) {
        this.onEnteredGame = callback;
    }

    /** True once the client is in the game phase and live packets can be sent. */
    public boolean isInGame() {
        return inGame;
    }

    /** Attaches the handshake listener. The client is about to send its intention packet. */
    public void begin() {
        connection.transitionInbound(
                net.minecraft.network.state.HandshakeStates.C2S,
                new HostHandshakeListener(connection, intention -> enterLogin()));
    }

    /** Switches to the login phase. */
    private void enterLogin() {
        // Outbound before inbound, as the vanilla server does: the client has sent its intention
        // and the next thing it reads must already be a login packet.
        connection.transitionOutbound(LoginStates.S2C);
        connection.transitionInbound(
                LoginStates.C2S,
                new HostLoginListener(connection, profile -> enterConfiguration()));
    }

    /** Switches to the configuration phase and replays the target's registries. */
    private void enterConfiguration() {
        HostConfigurationListener listener =
                new HostConfigurationListener(connection, handshake, c -> enterGame());
        connection.transitionOutbound(ConfigurationStates.S2C);
        connection.transitionInbound(ConfigurationStates.C2S, listener);
        listener.begin();
    }

    /**
     * Switches to the game phase and sends the join sequence.
     *
     * <p>The login packet is rebuilt rather than replayed -- see {@link JoinSequence} for why
     * replaying the target's is wrong in three separate ways.
     */
    private void enterGame() {
        // Deferred to the client thread, and retried, because the registries do not exist yet.
        // handleConfigurationFinished runs on the client thread and builds them there as it
        // constructs the client's play listener; this method runs on the Netty thread the moment
        // the acknowledgement arrives, which is strictly earlier. Reading them here directly
        // returned null and failed every session.
        MinecraftClient.getInstance().execute(this::tryEnterGame);
    }

    /**
     * Attempts the game-phase switch, rescheduling itself until the client's registries exist.
     *
     * <p>Runs on the client thread. The wait is normally a single tick -- the client is already
     * inside {@code handleConfigurationFinished} when this is first scheduled -- but it is bounded
     * rather than infinite so a session that will never be ready fails with a message instead of
     * hanging on the loading screen.
     */
    private void tryEnterGame() {
        if (inGame || !connection.isOpen()) {
            return;
        }
        DynamicRegistryManager access = registries.get();
        if (access == null) {
            if (++registryWaitTicks > MAX_REGISTRY_WAIT_TICKS) {
                SpectateClient.LOGGER.warn("spectate host: no registries after configuration");
                connection.disconnect(net.minecraft.text.Text.literal(
                        "Spectate: registry sync failed"));
                return;
            }
            MinecraftClient.getInstance().execute(this::tryEnterGame);
            return;
        }
        GameJoinS2CPacket targetLogin = decodeLogin(access);
        if (targetLogin == null) {
            connection.disconnect(net.minecraft.text.Text.literal(
                    "Spectate: target handshake incomplete"));
            return;
        }

        connection.transitionOutbound(
                PlayStateFactories.S2C.bind(
                        RegistryByteBuf.makeFactory(access)));
        connection.transitionInbound(
                PlayStateFactories.C2S.bind(
                        RegistryByteBuf.makeFactory(access),
                        new PlayStateFactories.PacketCodecModifierContext() {
                            @Override
                            public boolean isInCreativeMode() {
                                // The viewer is a spectator; this only gates a creative-mode packet
                                // it can never send.
                                return false;
                            }
                        }),
                new HostGameListener(connection));

        connection.send(JoinSequence.spectatorLogin(targetLogin));
        connection.send(JoinSequence.chunkRadius());
        // The viewer's own tab-list entry, which is where its client reads its game mode from --
        // not from the login packet above. Without it gameMode() is null, isSpectator() is false
        // and the viewer cannot pass through blocks.
        var self = JoinSequence.selfSpectating(
                net.minecraft.client.MinecraftClient.getInstance(), access);
        if (self != null) {
            connection.send(self);
        } else {
            SpectateClient.LOGGER.warn(
                    "spectate host: no self tab-list entry; the viewer will not be a spectator");
        }
        inGame = true;
        // Before any queued terrain is flushed: the cache centre has to be right first, or the
        // client discards every chunk as out of view range.
        if (hasPendingPosition) {
            applyPosition(pendingX, pendingY, pendingZ, pendingYaw, pendingPitch);
            SpectateClient.LOGGER.debug("spectate host: applied pending target position");
        }
        SpectateClient.LOGGER.debug("spectate host: viewer entered game phase");
        // After the position, so the queued chunks land against a correct view centre.
        onEnteredGame.run();
    }

    /**
     * Points the viewer's camera and chunk cache at the target.
     *
     * <p>The cache centre is the part that cannot be skipped: chunks outside it are discarded by
     * the client, and it starts at 0,0, so until this is sent every forwarded chunk is thrown away.
     */
    public void updateTargetPosition(double x, double y, double z, float yaw, float pitch) {
        // Remembered even when it cannot be applied yet. The target sends its position before the
        // terrain, but that position can arrive before the viewer's client has finished login --
        // and dropping it then left the chunk cache centred on 0,0, so every chunk that followed
        // was rejected with "Ignoring chunk since it's not in the view range". The pending value
        // is applied by enterGame().
        pendingX = x;
        pendingY = y;
        pendingZ = z;
        pendingYaw = yaw;
        pendingPitch = pitch;
        hasPendingPosition = true;
        if (!inGame || !connection.isOpen()) {
            return;
        }
        applyPosition(x, y, z, yaw, pitch);
    }

    private void applyPosition(double x, double y, double z, float yaw, float pitch) {
        // The centre follows the VIEWER's camera when it has one, and the target otherwise.
        //
        // The centre decides which arriving chunks the viewer's client keeps: it discards anything
        // further than its view radius from the centre, at the instant the chunk arrives, with no
        // later widening to save it. Centring it on the target looks right -- the capture is
        // clipped around the target, so that is where the chunks come from -- but it is wrong as
        // soon as the viewer stops riding the target. With free camera on, a viewer that flies
        // away is outside its own centre's radius, so terrain around it is thrown away on
        // arrival, and terrain around the target that arrived while it was away was thrown away
        // too. Flying back then showed a hole that never filled, because a server does not resend
        // a chunk and the viewer's client believes it already decided about that one.
        //
        // Keyed to the viewer, chunks are kept relative to what the viewer is actually looking at,
        // which is what the cache centre is for. The capture still sends terrain around the
        // target, so a viewer that flies beyond the streamed region sees its edge -- but it sees
        // everything that was sent, which it did not before.
        ChunkPos chunk = viewerChunk();
        if (chunk == null) {
            chunk = new ChunkPos(
                    net.minecraft.util.math.ChunkSectionPos.getSectionCoord(x),
                    net.minecraft.util.math.ChunkSectionPos.getSectionCoord(z));
        }
        connection.send(JoinSequence.chunkCenter(chunk.x, chunk.z));
        // The position is sent once, not on every report. A position packet moves the viewer's
        // player, and these arrive five times a second -- so resending it holds the viewer on top
        // of the target and makes flying impossible, since every input is undone within four
        // ticks. One is still required, to put the viewer somewhere the streamed terrain actually
        // is rather than at the origin.
        if (!sentInitialPosition) {
            sentInitialPosition = true;
            // Placed behind and above the target rather than on it. The viewer's own position is
            // the target's, and a camera inside another player's model sees the inside of that
            // model -- which renders as nothing and looks exactly like the target failing to
            // spawn. Offset along the view direction so the target is in front of the viewer at
            // the moment the world appears.
            double yawRad = Math.toRadians(yaw);
            double behindX = x + Math.sin(yawRad) * SPAWN_OFFSET_BLOCKS;
            double behindZ = z - Math.cos(yawRad) * SPAWN_OFFSET_BLOCKS;
            connection.send(JoinSequence.position(
                    behindX, y + 1.0, behindZ, yaw, pitch));
        }
    }

    /** The chunk centre last sent, so an unchanged centre is not resent every tick. */
    private volatile int lastCentreX = Integer.MIN_VALUE;
    private volatile int lastCentreZ = Integer.MIN_VALUE;

    /**
     * Re-centres the viewer's chunk cache on its own camera. Called once per client tick.
     *
     * <p>Per-tick rather than only when the target reports, because the two move independently:
     * with free camera on, the viewer can fly a long way while the target stands still, and the
     * target's position reports are the only other thing that would move the centre. Without this
     * the centre stayed wherever the last report put it and chunks arriving around the viewer were
     * discarded.
     *
     * <p>Sent only when the chunk actually changes. The centre is a packet, and this runs twenty
     * times a second.
     */
    public void tickViewerCentre() {
        if (!inGame || !connection.isOpen()) {
            return;
        }
        ChunkPos chunk = viewerChunk();
        if (chunk == null) {
            return;
        }
        if (chunk.x == lastCentreX && chunk.z == lastCentreZ) {
            return;
        }
        lastCentreX = chunk.x;
        lastCentreZ = chunk.z;
        connection.send(JoinSequence.chunkCenter(chunk.x, chunk.z));
    }

    /**
     * The chunk the viewer's own camera is in, or null before it has a player.
     *
     * <p>Read from the viewer's client rather than tracked from packets: this runs in the viewer's
     * process, so its camera position is simply available, and a value derived from what the host
     * last sent would be exactly the stale figure this is here to stop using.
     */
    private static ChunkPos viewerChunk() {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        var camera = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        return camera == null ? null : camera.getChunkPos();
    }

    /**
     * Forces the viewer back onto the target's position.
     *
     * <p>Used by the locked camera when it re-attaches, and when a session starts, so the viewer
     * does not stay wherever a previous free-flight left it.
     */
    public boolean recentreOnTarget() {
        if (!inGame || !connection.isOpen() || !hasPendingPosition) {
            return false;
        }
        // Offset behind the target exactly as the initial placement is, and for the same reason: a
        // camera inside another player's model renders the inside of that model, which looks like
        // the target having vanished.
        double yawRad = Math.toRadians(pendingYaw);
        double behindX = pendingX + Math.sin(yawRad) * SPAWN_OFFSET_BLOCKS;
        double behindZ = pendingZ - Math.cos(yawRad) * SPAWN_OFFSET_BLOCKS;
        connection.send(JoinSequence.position(
                behindX, pendingY + 1.0, behindZ, pendingYaw, pendingPitch));
        return true;
    }

    /**
     * Moves the target's entity in the viewer's world.
     *
     * <p>Synthesized from the target's position reports rather than forwarded, because a client's
     * own movement never appears in its inbound stream: it moved itself, so the server has nothing
     * to tell it. Every other entity moves through forwarded packets as normal; the target is the
     * one entity that needs this, and it is the one a viewer is watching.
     *
     * <p>Sent as an absolute position sync, which is what a server sends for a player that has
     * moved far enough to need one -- the relative move packets carry deltas this has no basis to
     * compute. The head is rotated by a separate packet, since the position sync carries only the
     * body's yaw.
     */
    public void moveTargetEntity(int entityId, double x, double y, double z,
                                 float yaw, float pitch, float headYaw, boolean onGround) {
        if (!inGame || !connection.isOpen()) {
            return;
        }
        connection.send(new net.minecraft.network.packet.s2c.play.EntityPositionSyncS2CPacket(
                entityId,
                new net.minecraft.entity.EntityPosition(
                        new net.minecraft.util.math.Vec3d(x, y, z),
                        net.minecraft.util.math.Vec3d.ZERO,
                        yaw, pitch),
                onGround));
        // The head is rotated separately. The position sync above carries only the body yaw, and a
        // player's head turns independently of its body -- so with no head packet the head held
        // whatever angle it had when it spawned, which looked like the head locking up after a
        // while rather than never having moved at all.
        connection.send(rotateHead(entityId, headYaw));
    }

    /**
     * Builds a head-rotation packet for an entity the host cannot reach.
     *
     * <p>Through the codec rather than the constructor: the only public constructor takes an
     * {@code Entity}, and the entity lives in the viewer's world, which the host does not reach
     * into. Writing the wire format is the same approach {@code TabList} takes, and for the same
     * reason. The format is a var-int id and the angle packed into a byte.
     */
    private static net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket rotateHead(
            int entityId, float headYaw) {
        net.minecraft.network.PacketByteBuf buf =
                new net.minecraft.network.PacketByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeVarInt(entityId);
        buf.writeByte((byte) Math.floor(headYaw * 256.0F / 360.0F));
        return net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket.CODEC
                .decode(buf);
    }

    /**
     * Swings the target's arm in the viewer's world.
     *
     * <p>Synthesized for the same reason as its movement: an arm swing is an animation the server
     * sends to the <em>other</em> clients, so it never appears in the target's own inbound stream
     * and the target mined, attacked and placed blocks without ever moving its arms.
     *
     * @param action a {@code EntityAnimationS2CPacket} action id
     */
    public void swingTargetArm(int entityId, int action) {
        if (!inGame || !connection.isOpen()) {
            return;
        }
        net.minecraft.network.PacketByteBuf buf =
                new net.minecraft.network.PacketByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeVarInt(entityId);
        buf.writeByte(action);
        connection.send(net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket.CODEC
                .decode(buf));
    }

    /**
     * Releases the client's loading screen, once.
     *
     * <p>Called after the first chunks have been forwarded rather than immediately on entering the
     * game phase: the client goes on to wait for its own chunk to be renderable, and announcing
     * before any terrain exists just moves the wait rather than removing it.
     */
    public void announceChunksLoaded() {
        if (!inGame || announcedChunksLoaded || !connection.isOpen()) {
            return;
        }
        announcedChunksLoaded = true;
        connection.send(JoinSequence.levelChunksLoaded());
        SpectateClient.LOGGER.debug("spectate host: announced level chunks loaded");
    }

    /**
     * Decodes the target's login packet against the registries the client just built.
     *
     * <p>This is why the packet travelled as bytes: its dimension-type holder resolves against the
     * target server's registries, which only exist once the replayed configuration has been
     * applied. Decoding it any earlier would resolve against the wrong set, or against none at all
     * when the session was started from the main menu.
     */
    private GameJoinS2CPacket decodeLogin(DynamicRegistryManager access) {
        if (loginWire == null) {
            SpectateClient.LOGGER.warn("spectate host: no login packet recorded");
            return null;
        }
        try {
            Packet<?> decoded = PacketWire.decode(PacketWire.gameCodec(access), loginWire);
            if (decoded instanceof GameJoinS2CPacket login) {
                return login;
            }
            SpectateClient.LOGGER.warn("spectate host: handshake login slot held {}",
                    decoded.getClass().getSimpleName());
            return null;
        } catch (RuntimeException e) {
            SpectateClient.LOGGER.warn("spectate host: could not decode the login packet", e);
            return null;
        }
    }

    /**
     * Sets one entity's pose on the viewer, as a metadata update.
     *
     * <p>Synthesised rather than forwarded, because the target's own pose is never in its
     * inbound stream: a client sets its pose locally and the server tells only the other
     * clients. Waiting for that echo to come back round is what made the target's sneak lag
     * behind everything else it did.
     */
    public void setEntityPose(int entityId, int poseOrdinal) {
        if (!inGame || !connection.isOpen()) {
            return;
        }
        net.minecraft.entity.EntityPose[] poses = net.minecraft.entity.EntityPose.values();
        if (poseOrdinal < 0 || poseOrdinal >= poses.length) {
            return;
        }
        java.util.List<net.minecraft.entity.data.DataTracker.SerializedEntry<?>> entries =
                java.util.List.of(net.minecraft.entity.data.DataTracker.SerializedEntry.of(
                        dev.spog.spegtate.mixin.EntityPoseAccessor.spegtate$pose(),
                        poses[poseOrdinal]));
        connection.send(
                new net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket(
                        entityId, entries));
    }
}
