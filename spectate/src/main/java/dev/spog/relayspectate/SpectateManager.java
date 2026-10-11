package dev.spog.relayspectate;

import dev.spog.relayspectate.capture.CaptureHooks;
import dev.spog.relayspectate.capture.CaptureSession;
import dev.spog.relayspectate.capture.ChunkSweep;
import dev.spog.relayspectate.capture.HandshakeRecorder;
import dev.spog.relayspectate.capture.PacketWire;
import dev.spog.relayspectate.host.ViewerSession;
import dev.spog.relayspectate.net.Frames;
import dev.spog.relayspectate.net.SpectateClientSocket;
import dev.spog.relayspectate.net.SpectateProtocol;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Owns the addon's live state: the socket to the spectate service, the capture this client is
 * serving, and the session it is viewing.
 *
 * <p>The two roles are independent. A client can be streaming to a viewer while watching someone
 * else, so nothing here assumes one excludes the other.
 *
 * <p>Threading: frames arrive on the socket's receive thread, and anything touching the game's
 * world or screens has to happen on the client thread. The queue in {@link #pendingMainThread}
 * bridges the two -- {@link #tick()} drains it from the client thread.
 */
public final class SpectateManager {
    private static final SpectateManager INSTANCE = new SpectateManager();

    public static SpectateManager get() {
        return INSTANCE;
    }

    private final ConcurrentLinkedQueue<Runnable> pendingMainThread = new ConcurrentLinkedQueue<>();

    /** Report the target's position every 4 client ticks (5 Hz), matching Relay's own cadence. */
    private static final int POSITION_INTERVAL_TICKS = 4;

    private volatile SpectateClientSocket socket;
    private volatile ViewerSession viewing;
    private int positionTicks;
    private int viewerTicks;
    private boolean reportedUnavailable;

    /** The most recent denial's text, shown on the spectate screen. Cleared on a new request. */
    private volatile String lastDenial;

    private SpectateManager() {
    }

    /** The session this client is viewing, or null. */
    public ViewerSession viewing() {
        return viewing;
    }

    /** True if someone is currently watching this client. */
    public boolean isBeingWatched() {
        return CaptureHooks.isStreaming();
    }

    /** How many viewers are watching this client. */
    public int viewerCount() {
        CaptureSession session = CaptureHooks.session();
        return session == null ? 0 : session.viewers().size();
    }

    /** Opens the socket if it is not already up. */
    public void connect(String url) {
        SpectateClientSocket existing = socket;
        if (existing == null) {
            existing = new SpectateClientSocket(url, this::onFrame);
            socket = existing;
        }
        existing.ensureConnected();
    }

    public void disconnect() {
        // discardViewing rather than stopViewing: the socket is about to go, so there is nobody to
        // send a session-stop to, and the service ends the session when the connection drops.
        discardViewing();
        SpectateClientSocket s = socket;
        if (s != null) {
            s.close();
        }
        socket = null;
        CaptureHooks.onDisconnect();
    }

    /** Pushes this client's trust set to the service, so it knows who may watch. */
    public void sendTrust(java.util.Set<UUID> trusts) {
        SpectateClientSocket s = socket;
        if (s != null && s.isAuthenticated()) {
            s.sendTrust(trusts);
        }
    }

    /** Asks to begin watching a target. */
    public void requestSession(UUID target) {
        SpectateClientSocket s = socket;
        if (s == null || !s.isAuthenticated()) {
            SpectateClient.LOGGER.warn("spectate: not connected to the service");
            return;
        }
        // Refused while this client is in a world of its own. A session replaces the client's
        // world with the target's, so starting one from in-game would disconnect the player from
        // the server they are playing on -- and ending it could not put them back. Spectating is
        // a from-the-menu feature, which is also how it was asked for.
        if (Minecraft.getInstance().level != null) {
            SpectateClient.LOGGER.info("spectate: refused, leave your world first");
            return;
        }
        lastDenial = null;
        s.requestSession(target);
    }

    /** The most recent denial's message, or null if the last attempt was not refused. */
    public String lastDenial() {
        return lastDenial;
    }

    /** True if a session can be started right now. Drives the menu's own messaging. */
    public boolean canStartSession() {
        SpectateClientSocket s = socket;
        return s != null && s.isAuthenticated() && Minecraft.getInstance().level == null;
    }

    /**
     * Ends the session this client is viewing, telling the service to stop the capture.
     *
     * <p>For when the user stops watching. To tear down local state <em>without</em> cancelling
     * the session at the service, use {@link #discardViewing()}.
     */
    public void stopViewing() {
        boolean had = discardViewing();
        SpectateClientSocket s = socket;
        if (had && s != null && s.isConnected()) {
            s.stopSession();
        }
    }

    /**
     * Drops the local viewing session without sending anything to the service.
     *
     * <p>Separate from {@link #stopViewing()} because the two are not the same operation, and
     * conflating them broke every session: {@code beginViewing} cleared any previous session
     * first, which sent a session-stop for the session the service had just granted -- so the
     * target was told to start capturing and immediately told to stop.
     *
     * <p>Used when the service has already ended the session (a stream-end or a denial, where
     * there is nothing left to cancel) and when replacing one session with another.
     *
     * @return true if there was a session to drop
     */
    private boolean discardViewing() {
        ViewerSession session = viewing;
        viewing = null;
        if (session == null) {
            return false;
        }
        session.close();
        return true;
    }

    /**
     * Drains cross-thread work and, while streaming, reports this client's position.
     *
     * <p>Called once per client tick, from the client thread.
     */
    public void tick() {
        Runnable task;
        while ((task = pendingMainThread.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException e) {
                SpectateClient.LOGGER.warn("spectate: main-thread task failed", e);
            }
        }
        reportPositionIfStreaming();
        ViewerSession session = viewing;
        if (session != null) {
            session.tickCamera();
            // One summary a few seconds in, so a blank world can be told apart from a world whose
            // terrain never arrived without needing a packet-by-packet log.
            if (++viewerTicks == 100) {
                SpectateClient.LOGGER.info("spectate: {} chunk packets delivered after 5s",
                        session.chunksReceived());
            }
        } else {
            viewerTicks = 0;
        }
    }

    /**
     * Sends this client's position to its viewers, a few times a second.
     *
     * <p>Not derived from the forwarded stream: a client's own position is not in its inbound
     * packets, because it moved itself there. The viewer needs it both to place the camera and to
     * centre its chunk cache, and the second is what makes the forwarded terrain visible at all.
     *
     * <p>Also where a target with no world reports that fact, so a viewer waiting on it is denied
     * rather than left on a loading screen forever.
     */
    private void reportPositionIfStreaming() {
        CaptureSession session = CaptureHooks.session();
        if (session == null || !session.hasViewers()) {
            return;
        }
        SpectateClientSocket s = socket;
        if (s == null || !s.isAuthenticated()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            if (!reportedUnavailable) {
                reportedUnavailable = true;
                s.sendCaptureUnavailable();
            }
            return;
        }
        reportedUnavailable = false;
        if (++positionTicks < POSITION_INTERVAL_TICKS) {
            return;
        }
        positionTicks = 0;
        s.sendTargetPosition(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                mc.player.getYRot(), mc.player.getXRot());
    }

    /** Handles one frame from the service. Runs on the socket's receive thread. */
    private void onFrame(ByteBuffer frame) {
        switch (Frames.typeOf(frame)) {
            case SpectateProtocol.TYPE_SESSION_READY -> {
                UUID target = Frames.readUuid(frame);
                pendingMainThread.add(() -> beginViewing(target));
            }
            case SpectateProtocol.TYPE_SESSION_DENIED -> {
                byte reason = Frames.readDenyReason(frame);
                String detail = Frames.readDenyDetail(frame);
                SpectateClient.LOGGER.info("spectate denied ({}): {}", reason, detail);
                // Keep it for the screen: a denial that only reached the log looked to the user
                // like the button had silently done nothing.
                lastDenial = detail == null || detail.isBlank()
                        ? SpectateLang.string("error.failed") : detail;
                pendingMainThread.add(this::discardViewing);
            }
            case SpectateProtocol.TYPE_HANDSHAKE -> {
                byte[] payload = Frames.readPayload(frame);
                ViewerSession session = viewing;
                if (session != null) {
                    session.acceptHandshakePacket(payload);
                }
            }
            case SpectateProtocol.TYPE_LOGIN_PACKET -> {
                // Completing the handshake starts the session, which touches Minecraft, so it has
                // to happen on the client thread rather than here on the socket's.
                byte[] payload = Frames.readPayload(frame);
                pendingMainThread.add(() -> {
                    ViewerSession session = viewing;
                    if (session != null) {
                        session.acceptLoginPacket(payload);
                    }
                });
            }
            case SpectateProtocol.TYPE_GAME_PACKET -> {
                byte[] payload = Frames.readPayload(frame);
                ViewerSession session = viewing;
                if (session != null) {
                    session.feed(payload);
                }
            }
            case SpectateProtocol.TYPE_TARGET_POSITION -> {
                frame.get(); // type
                if (frame.remaining() >= 8 * 3 + 4 * 2) {
                    double x = frame.getDouble();
                    double y = frame.getDouble();
                    double z = frame.getDouble();
                    float yaw = frame.getFloat();
                    float pitch = frame.getFloat();
                    ViewerSession session = viewing;
                    if (session != null) {
                        session.applyTargetPosition(x, y, z, yaw, pitch);
                    }
                }
            }
            case SpectateProtocol.TYPE_STREAM_END -> pendingMainThread.add(this::discardViewing);
            case SpectateProtocol.TYPE_CAPTURE_START -> {
                UUID viewer = Frames.readUuid(frame);
                pendingMainThread.add(() -> beginCapture(viewer));
            }
            case SpectateProtocol.TYPE_CAPTURE_STOP -> {
                UUID viewer = Frames.readUuid(frame);
                pendingMainThread.add(() -> endCapture(viewer));
            }
            default -> { }
        }
    }

    /**
     * Starts a viewing session. On the client thread.
     *
     * <p>The handshake has not arrived yet at this point -- the target sends it right after being
     * told to capture -- so the session starts with an empty handshake and the frames that follow
     * fill it. That is why {@link ViewerSession} queues anything arriving before the game phase.
     */
    private void beginViewing(UUID target) {
        discardViewing();
        ViewerSession session = new ViewerSession(target);
        viewing = session;
        SpectateClient.LOGGER.info("spectate: awaiting handshake from {}", target);
    }

    /** Begins streaming to a viewer. On the client thread. */
    private void beginCapture(UUID viewer) {
        SpectateClientSocket s = socket;
        if (s == null) {
            return;
        }
        CaptureSession session = CaptureHooks.session();
        if (session == null) {
            session = new CaptureSession(s::sendGamePacket);
            CaptureHooks.setSession(session);
        }
        session.addViewer(viewer);

        if (!session.handshakeSent()) {
            EncodedHandshake encoded = encodeHandshake();
            if (encoded.isEmpty()) {
                // Deny rather than send a partial handshake. A viewer given registries without
                // tags does not fail gracefully: its registry load throws on every enchantment
                // that references an item tag, and it disconnects with a wall of
                // "Missing tag" errors that say nothing about spectating.
                SpectateClient.LOGGER.warn("spectate: refusing {} -- no usable handshake", viewer);
                session.removeViewer(viewer);
                s.sendCaptureUnavailable();
                return;
            }
            s.sendHandshake(encoded.configuration());
            s.sendLoginPacket(encoded.login());
            session.markHandshakeSent();
            // Position before terrain, not after. The viewer's chunk cache discards anything
            // outside its view centre, and that centre starts at 0,0 -- so chunks sent before the
            // first position would be thrown away on arrival.
            sendPositionNow(s);
            sendLoadedTerrain(s, session);
        }
        SpectateClient.LOGGER.info("spectate: streaming to {}", viewer);
    }

    /** Stops streaming to a viewer. On the client thread. */
    private void endCapture(UUID viewer) {
        CaptureSession session = CaptureHooks.session();
        if (session == null) {
            return;
        }
        session.removeViewer(viewer);
        if (!session.hasViewers()) {
            CaptureHooks.setSession(null);
            SpectateClientSocket s = socket;
            if (s != null) {
                s.sendStreamEnd();
            }
        }
        SpectateClient.LOGGER.info("spectate: stopped streaming to {}", viewer);
    }

    /** Reports this client's position immediately, outside the usual 5 Hz cadence. */
    private void sendPositionNow(SpectateClientSocket s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        s.sendTargetPosition(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                mc.player.getYRot(), mc.player.getXRot());
    }

    /**
     * Sends the viewer the terrain the target is already standing in.
     *
     * <p>The passive capture cannot supply this: a target that has been connected for more than a
     * few seconds already holds its chunks, and a server does not re-send them. So the first
     * terrain a viewer sees has to be rebuilt from the target's own chunk cache. See
     * {@link ChunkSweep}.
     */
    private void sendLoadedTerrain(SpectateClientSocket s, CaptureSession session) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener listener = mc.getConnection();
        if (listener == null) {
            return;
        }
        StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec =
                PacketWire.gameCodec(listener.registryAccess());
        int chunks = 0;
        for (var chunk : ChunkSweep.loadedChunksAround(mc)) {
            try {
                s.sendGamePacket(PacketWire.encode(codec, chunk));
                chunks++;
            } catch (RuntimeException e) {
                SpectateClient.LOGGER.debug("spectate: could not send a packed chunk", e);
            }
        }
        // Entities after terrain: the client drops an entity whose chunk it does not have.
        int entities = 0;
        for (var packet : ChunkSweep.entitiesAround(mc)) {
            try {
                @SuppressWarnings("unchecked")
                Packet<? super ClientGamePacketListener> game =
                        (Packet<? super ClientGamePacketListener>) packet;
                s.sendGamePacket(PacketWire.encode(codec, game));
                entities++;
            } catch (RuntimeException e) {
                SpectateClient.LOGGER.debug("spectate: could not send a packed entity", e);
            }
        }
        SpectateClient.LOGGER.info("spectate: sent {} chunks and {} entity packets",
                chunks, entities);
    }

    /** A handshake encoded for the wire: configuration packets, then the login packet. */
    public record EncodedHandshake(List<byte[]> configuration, byte[] login) {
        boolean isEmpty() {
            return configuration.isEmpty() || login == null;
        }
    }

    /**
     * Re-encodes the recorded handshake to wire bytes, split by phase.
     *
     * <p>The split is required, not tidiness: configuration packets and the login packet use
     * different packet-id tables and different buffer types, so one codec cannot encode both. See
     * {@link PacketWire} for why picking the wrong one fails only at runtime.
     *
     * <p>Encoded here rather than at record time because the game codec needs the connection's
     * registries, and because a handshake is sent once per session but recorded once per
     * connection -- so the cost belongs on the rarer path.
     */
    private EncodedHandshake encodeHandshake() {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (listener == null) {
            return new EncodedHandshake(List.of(), null);
        }
        HandshakeRecorder recorder = CaptureHooks.handshake();
        if (!recorder.isComplete()) {
            // Almost always because this client was already in-game when the addon loaded, so the
            // configuration packets had been and gone. Reconnecting to the server records them.
            SpectateClient.LOGGER.warn(
                    "spectate: handshake incomplete, cannot serve a viewer ({}). "
                            + "Reconnect to the server to record it.", recorder.describe());
            return new EncodedHandshake(List.of(), null);
        }
        StreamCodec<ByteBuf, Packet<? super ClientConfigurationPacketListener>> configCodec =
                PacketWire.configurationCodec();
        StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> gameCodec =
                PacketWire.gameCodec(listener.registryAccess());

        List<byte[]> configuration = new ArrayList<>();
        byte[] login = null;
        for (HandshakeRecorder.Recorded recorded : recorder.snapshot()) {
            Packet<?> packet = recorded.packet();
            try {
                if (packet instanceof ClientboundLoginPacket) {
                    @SuppressWarnings("unchecked")
                    Packet<? super ClientGamePacketListener> game =
                            (Packet<? super ClientGamePacketListener>) packet;
                    login = PacketWire.encode(gameCodec, game);
                } else {
                    @SuppressWarnings("unchecked")
                    Packet<? super ClientConfigurationPacketListener> config =
                            (Packet<? super ClientConfigurationPacketListener>) packet;
                    configuration.add(PacketWire.encode(configCodec, config));
                }
            } catch (RuntimeException e) {
                // An unencodable handshake packet means the viewer cannot build a correct world, so
                // the whole session is refused rather than started with a partial registry set.
                SpectateClient.LOGGER.warn("spectate: could not encode handshake packet {}",
                        packet.getClass().getSimpleName(), e);
                return new EncodedHandshake(List.of(), null);
            }
        }
        SpectateClient.LOGGER.info("spectate: sending handshake -- {} ({} config frames, login={}B)",
                recorder.describe(), configuration.size(),
                login == null ? 0 : login.length);
        return new EncodedHandshake(configuration, login);
    }
}
