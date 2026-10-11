package dev.spog.relayspectate.host;

import dev.spog.relayspectate.SpectateClient;
import dev.spog.relayspectate.capture.PacketWire;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.world.level.ChunkPos;

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
    private final Connection connection;
    private final List<Packet<?>> handshake;
    private final byte[] loginWire;
    private final Supplier<RegistryAccess> registries;

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
    public HostDriver(Connection connection, List<Packet<?>> handshake, byte[] loginWire,
                      Supplier<RegistryAccess> registries) {
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
        connection.setupInboundProtocol(
                net.minecraft.network.protocol.handshake.HandshakeProtocols.SERVERBOUND,
                new HostHandshakeListener(connection, intention -> enterLogin()));
    }

    /** Switches to the login phase. */
    private void enterLogin() {
        // Outbound before inbound, as the vanilla server does: the client has sent its intention
        // and the next thing it reads must already be a login packet.
        connection.setupOutboundProtocol(LoginProtocols.CLIENTBOUND);
        connection.setupInboundProtocol(
                LoginProtocols.SERVERBOUND,
                new HostLoginListener(connection, profile -> enterConfiguration()));
    }

    /** Switches to the configuration phase and replays the target's registries. */
    private void enterConfiguration() {
        HostConfigurationListener listener =
                new HostConfigurationListener(connection, handshake, c -> enterGame());
        connection.setupOutboundProtocol(ConfigurationProtocols.CLIENTBOUND);
        connection.setupInboundProtocol(ConfigurationProtocols.SERVERBOUND, listener);
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
        Minecraft.getInstance().execute(this::tryEnterGame);
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
        if (inGame || !connection.isConnected()) {
            return;
        }
        RegistryAccess access = registries.get();
        if (access == null) {
            if (++registryWaitTicks > MAX_REGISTRY_WAIT_TICKS) {
                SpectateClient.LOGGER.warn("spectate host: no registries after configuration");
                connection.disconnect(net.minecraft.network.chat.Component.literal(
                        "Spectate: registry sync failed"));
                return;
            }
            Minecraft.getInstance().execute(this::tryEnterGame);
            return;
        }
        ClientboundLoginPacket targetLogin = decodeLogin(access);
        if (targetLogin == null) {
            connection.disconnect(net.minecraft.network.chat.Component.literal(
                    "Spectate: target handshake incomplete"));
            return;
        }

        connection.setupOutboundProtocol(
                GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                        RegistryFriendlyByteBuf.decorator(access)));
        connection.setupInboundProtocol(
                GameProtocols.SERVERBOUND_TEMPLATE.bind(
                        RegistryFriendlyByteBuf.decorator(access),
                        new GameProtocols.Context() {
                            @Override
                            public boolean hasInfiniteMaterials() {
                                // The viewer is a spectator; this only gates a creative-mode packet
                                // it can never send.
                                return false;
                            }
                        }),
                new HostGameListener(connection));

        connection.send(JoinSequence.spectatorLogin(targetLogin));
        connection.send(JoinSequence.chunkRadius());
        inGame = true;
        // Before any queued terrain is flushed: the cache centre has to be right first, or the
        // client discards every chunk as out of view range.
        if (hasPendingPosition) {
            applyPosition(pendingX, pendingY, pendingZ, pendingYaw, pendingPitch);
            SpectateClient.LOGGER.info("spectate host: applied pending target position");
        }
        SpectateClient.LOGGER.info("spectate host: viewer entered game phase");
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
        if (!inGame || !connection.isConnected()) {
            return;
        }
        applyPosition(x, y, z, yaw, pitch);
    }

    private void applyPosition(double x, double y, double z, float yaw, float pitch) {
        ChunkPos chunk = new ChunkPos(
                net.minecraft.core.SectionPos.blockToSectionCoord(x),
                net.minecraft.core.SectionPos.blockToSectionCoord(z));
        // The centre follows the target always: it decides which forwarded chunks the client will
        // accept, and the capture is clipped to a radius around the target, so centring anywhere
        // else would reject the very chunks being sent.
        connection.send(JoinSequence.chunkCenter(chunk.x(), chunk.z()));
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

    /**
     * Forces the viewer back onto the target's position.
     *
     * <p>Used by the locked camera when it re-attaches, and when a session starts, so the viewer
     * does not stay wherever a previous free-flight left it.
     */
    public void recentreOnTarget() {
        if (!inGame || !connection.isConnected() || !hasPendingPosition) {
            return;
        }
        connection.send(JoinSequence.position(
                pendingX, pendingY, pendingZ, pendingYaw, pendingPitch));
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
        if (!inGame || !connection.isConnected()) {
            return;
        }
        connection.send(new net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket(
                entityId,
                new net.minecraft.world.entity.PositionMoveRotation(
                        new net.minecraft.world.phys.Vec3(x, y, z),
                        net.minecraft.world.phys.Vec3.ZERO,
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
    private static net.minecraft.network.protocol.game.ClientboundRotateHeadPacket rotateHead(
            int entityId, float headYaw) {
        net.minecraft.network.FriendlyByteBuf buf =
                new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeVarInt(entityId);
        buf.writeByte((byte) Math.floor(headYaw * 256.0F / 360.0F));
        return net.minecraft.network.protocol.game.ClientboundRotateHeadPacket.STREAM_CODEC
                .decode(buf);
    }

    /**
     * Swings the target's arm in the viewer's world.
     *
     * <p>Synthesized for the same reason as its movement: an arm swing is an animation the server
     * sends to the <em>other</em> clients, so it never appears in the target's own inbound stream
     * and the target mined, attacked and placed blocks without ever moving its arms.
     *
     * @param action a {@code ClientboundAnimatePacket} action id
     */
    public void swingTargetArm(int entityId, int action) {
        if (!inGame || !connection.isConnected()) {
            return;
        }
        net.minecraft.network.FriendlyByteBuf buf =
                new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeVarInt(entityId);
        buf.writeByte(action);
        connection.send(net.minecraft.network.protocol.game.ClientboundAnimatePacket.STREAM_CODEC
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
        if (!inGame || announcedChunksLoaded || !connection.isConnected()) {
            return;
        }
        announcedChunksLoaded = true;
        connection.send(JoinSequence.levelChunksLoaded());
        SpectateClient.LOGGER.info("spectate host: announced level chunks loaded");
    }

    /**
     * Decodes the target's login packet against the registries the client just built.
     *
     * <p>This is why the packet travelled as bytes: its dimension-type holder resolves against the
     * target server's registries, which only exist once the replayed configuration has been
     * applied. Decoding it any earlier would resolve against the wrong set, or against none at all
     * when the session was started from the main menu.
     */
    private ClientboundLoginPacket decodeLogin(RegistryAccess access) {
        if (loginWire == null) {
            SpectateClient.LOGGER.warn("spectate host: no login packet recorded");
            return null;
        }
        try {
            Packet<?> decoded = PacketWire.decode(PacketWire.gameCodec(access), loginWire);
            if (decoded instanceof ClientboundLoginPacket login) {
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
}
