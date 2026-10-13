package dev.spog.spegtate;

import dev.spog.spegtate.capture.CaptureHooks;
import dev.spog.spegtate.capture.CaptureSession;
import dev.spog.spegtate.capture.ChunkSweep;
import dev.spog.spegtate.capture.HandshakeRecorder;
import dev.spog.spegtate.capture.PacketWire;
import dev.spog.spegtate.capture.TabList;
import dev.spog.spegtate.host.ViewerSession;
import dev.spog.spegtate.net.Frames;
import dev.spog.spegtate.net.SpectateClientSocket;
import dev.spog.spegtate.net.SpectateProtocol;
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
    /** Light grey, so a spectate notice reads as a note rather than a warning. */
    private static final int CHAT_COLOUR = 0xBBBBBB;

    private static final SpectateManager INSTANCE = new SpectateManager();

    public static SpectateManager get() {
        return INSTANCE;
    }

    private final ConcurrentLinkedQueue<Runnable> pendingMainThread = new ConcurrentLinkedQueue<>();

    /**
     * Report the target's position every client tick (20 Hz).
     *
     * <p>Relay's own cadence is 4 ticks, which is ample for a map marker but not for a body a
     * viewer is watching: each report becomes a teleport of the target's entity, and at 5 Hz the
     * entity jumps four ticks' worth of travel at a time. The client interpolates between the
     * positions it is given, so the fix is to give it one every tick -- which is also what a real
     * server sends for a player in view.
     */
    private static final int POSITION_INTERVAL_TICKS = 1;

    private volatile SpectateClientSocket socket;
    private volatile ViewerSession viewing;
    /** Set once the first sweep has fully drained, so the log line is not repeated per tick. */
    private boolean reportedSweepDone;
    private int positionTicks;
    private int viewerTicks;
    /** Swing time seen on the previous report, to detect a restarted swing. */
    private int lastSwingTime;
    /** Whether a swing was running on the previous report, to detect a newly started one. */
    private boolean lastSwinging;

    /**
     * How many terrain frames to hand the socket per tick.
     *
     * <p>The sweep produces several hundred frames at once, and queueing them all in a single tick
     * delivered none of them: the target logged "sent 421 chunks and 21 entity packets" while the
     * viewer received zero and nothing reported a failure, because the send chain's error handler
     * discarded them silently. A burst that large outruns what the JDK's WebSocket will buffer, so
     * it is spread over ticks instead -- 32 per tick clears 440 in under three quarters of a
     * second, which is well inside the client's own 30-second chunk-loading timeout.
     */
    private static final int TERRAIN_FRAMES_PER_TICK = 32;

    /**
     * Captured frames still to be handed to the socket, drained by {@link #tick()}.
     *
     * <p>Concurrent because live capture is queued from the Netty event loop while the sweep is
     * queued and the queue drained on the client thread.
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> terrainBacklog =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * Frames that must reach the viewer before anything already queued.
     *
     * <p>Drained ahead of {@link #terrainBacklog} and only ever holding tab-list packets, which
     * every player spawn depends on. See {@link #queueAhead}.
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<byte[]> priorityBacklog =
            new java.util.concurrent.ConcurrentLinkedQueue<>();


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

    /**
     * Pushes this client's consent to the service; see {@link SpectateConfig#allowSpectators}.
     *
     * <p>Guarded on authentication because the service ignores a trust-update from a session it
     * has not verified -- sending one earlier would be silently dropped and the client would
     * believe it had reported its consent.
     */
    public void sendConsent(boolean allowsViewers) {
        SpectateClientSocket s = socket;
        if (s != null && s.isAuthenticated()) {
            s.sendConsent(allowsViewers);
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

    /** True while this client is watching someone, for the pause menu's disconnect button. */
    public boolean isViewing() {
        return viewing != null;
    }

    /**
     * Ends the session this client is viewing, telling the service to stop the capture.
     *
     * <p>For when the user stops watching. To tear down local state <em>without</em> cancelling
     * the session at the service, use {@link #discardViewing()}.
     */
    public void stopViewing() {
        discardViewing();
        SpectateClientSocket s = socket;
        // Sent whether or not a session was still held locally. The stream ending -- the target
        // leaving, or a STREAM_END -- discards the session here, and the old guard then skipped
        // the frame, so the service went on believing this client was watching and refused the
        // next request as busy. That is the restart that does nothing until the menu is reopened
        // and stop pressed a second time. The service is the authority on what it holds, and a
        // stop for a session it has already forgotten is ignored there.
        if (s != null && s.isConnected()) {
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
        drainTerrainBacklog();
        reportPositionIfStreaming();
        reportPeerPositions();
        resendTabListIfTexturesArrived();
        sweepNewTerrain();
        ViewerSession session = viewing;
        if (session != null) {
            session.tickCamera();
            requestMissingChunks(session);
            // Kept, unlike the periodic summaries this sat alongside: it fires only when a
            // session has failed, and the alternative is vanilla's "Timed out while waiting for
            // the client to load chunks" 30 seconds later, which names neither spectating nor the
            // stage that failed.
            if (++viewerTicks == 100) {
                if (session.chunksReceived() == 0) {
                    // Which side went quiet, and whether the target ever sent a position, since
                    // terrain is sent immediately after one.
                    SpectateClient.LOGGER.warn(
                            "spectate: no terrain after 5s -- inGame={} positionSeen={}",
                            session.isInGame(), session.hasSeenPosition());
                }
            }
        } else {
            viewerTicks = 0;
        }
    }

    /**
     * Hands a bounded slice of the swept terrain to the socket.
     *
     * <p>Called once per client tick. See {@link #TERRAIN_FRAMES_PER_TICK} for why the sweep is not
     * simply sent where it is produced.
     */
    /**
     * Queues one captured frame behind anything already waiting.
     *
     * <p>Live capture used to go straight to the socket while the initial sweep went through the
     * backlog, so the two raced -- and the sweep is hundreds of frames drained at
     * {@link #TERRAIN_FRAMES_PER_TICK} per tick, which takes seconds. Live packets therefore
     * arrived at the viewer well before the terrain and entities they referred to, and the client
     * silently discards those: handleSetEntityData looks up the entity id and returns if it does
     * not exist yet, so a dropped item's stack -- which is sent once, as entity data -- was lost
     * for good and the item rendered as nothing. Sounds and effects for not-yet-existent positions
     * went the same way.
     *
     * <p>One queue keeps capture in the order the target saw it, which is the order a viewer has
     * to receive it in. The backlog drains at a fixed rate per tick either way, so this does not
     * make the sweep slower; it only stops later packets overtaking it.
     */

    /**
     * Queues a live captured packet, sending tab-list updates ahead of the backlog.
     *
     * <p>A player who walks into the target's view while the sweep is still draining produces a
     * tab-list entry and a spawn. Both go through this queue, and the spawn is discarded if the
     * entry has not arrived -- so the entry cannot be allowed to sit behind several hundred chunk
     * frames. The spawn itself stays in order, behind the terrain it needs.
     */
    private void queueCaptured(Packet<?> packet, byte[] wire) {
        // Chunks the live stream delivers are recorded as sent, so the trailing sweep does not
        // rebuild and resend terrain the viewer already has. Without this every chunk inside the
        // radius would be sent twice -- once live and once by the next sweep.
        if (packet instanceof net.minecraft.network.protocol.game
                .ClientboundLevelChunkWithLightPacket chunk) {
            sentChunks.add(chunkKey(chunk.getX(), chunk.getZ()));
        }
        if (packet instanceof net.minecraft.network.protocol.game
                .ClientboundPlayerInfoUpdatePacket
                || packet instanceof net.minecraft.network.protocol.game
                        .ClientboundPlayerInfoRemovePacket) {
            queueAhead(wire);
            return;
        }
        terrainBacklog.add(wire);
    }

    /**
     * Queues a frame that every later frame depends on, ahead of the backlog.
     *
     * <p>Only for tab-list packets. A player spawn is discarded outright by
     * {@code createEntityFromPacket} when the client has no tab-list entry for that player, and
     * the spawn is never repeated -- so an entry arriving after the spawn it belongs to loses that
     * player for the rest of the session. Terrain has no such dependency: a chunk that arrives a
     * second later is simply a second later.
     *
     * <p>Jumping the queue is therefore safe here and nowhere else. It reorders a packet ahead of
     * frames that cannot depend on it, while the ordering the backlog exists to enforce -- entity
     * data after its spawn, effects after the world they happen in -- is untouched.
     *
     * <p>A separate queue rather than splicing into the main one: draining and refilling a
     * concurrent queue is not atomic, and capture writes to it from the Netty event loop, so a
     * frame arriving mid-splice could be reordered or lost.
     */
    private void queueAhead(byte[] wire) {
        priorityBacklog.add(wire);
    }

    /**
     * How many frames to hand the socket this tick.
     *
     * <p>The limit exists to stop a sweep's few hundred frames being written in one go, which
     * overran the send buffer and lost frames silently. It must not become a permanent speed limit
     * on live capture: a busy server can exceed 32 packets a tick, and a cap below the arrival
     * rate would make the queue grow without bound and the viewer fall steadily further behind.
     *
     * <p>So the cap applies only while a backlog is actually being worked off. Once the queue is
     * down to roughly one tick's worth, it drains completely each tick and live capture flows at
     * the rate it arrives.
     */
    private int frameBudget() {
        int waiting = terrainBacklog.size();
        return waiting <= TERRAIN_FRAMES_PER_TICK ? waiting : TERRAIN_FRAMES_PER_TICK;
    }

    private void drainTerrainBacklog() {
        if (terrainBacklog.isEmpty() && priorityBacklog.isEmpty()) {
            return;
        }
        SpectateClientSocket s = socket;
        if (s == null) {
            terrainBacklog.clear();
            priorityBacklog.clear();
            return;
        }
        // Priority frames are not rate limited. There are only ever a handful, and the frames
        // waiting behind them are the ones that depend on them.
        byte[] ahead;
        while ((ahead = priorityBacklog.poll()) != null) {
            s.sendGamePacket(ahead);
        }
        int budget = frameBudget();
        int sent = 0;
        byte[] wire;
        while (sent < budget && (wire = terrainBacklog.poll()) != null) {
            s.sendGamePacket(wire);
            sent++;
        }
        if (terrainBacklog.isEmpty() && !reportedSweepDone) {
            reportedSweepDone = true;
            SpectateClient.LOGGER.debug("spectate: finished sending swept terrain");
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
                mc.player.getYRot(), mc.player.getXRot(),
                mc.player.getId(), mc.player.getYHeadRot(), mc.player.onGround(),
                swingAction(mc.player));
    }

    /**
     * The animation id for a swing that has just started, or -1 for no swing.
     *
     * <p>One packet per swing, not one per tick: a repeat restarts the animation and the arm never
     * completes its arc.
     *
     * <p>Detecting the start is where two earlier attempts went wrong, both by reasoning about
     * {@code swingTime}'s values instead of its transitions. {@code swing()} sets it to -1 and
     * {@code swinging} true; {@code updateSwingTime} then increments it every tick and clears both
     * once the swing has run its duration. Crucially this runs in {@code LocalPlayer.tick}, which
     * is <em>before</em> this sampling at {@code END_CLIENT_TICK} -- so the -1 is never observed
     * here, and neither is 0 reliably. Matching on those values made a swing register only when
     * the sampling happened to land right, which is why swings mostly went missing.
     *
     * <p>What is reliable is the transition: a swing is new if it was not running a tick ago, or if
     * it was running and {@code swingTime} has gone down, which only happens on a restart. That
     * holds wherever in the tick this is sampled.
     */
    private int swingAction(net.minecraft.client.player.LocalPlayer player) {
        int time = player.swingTime;
        int previous = lastSwingTime;
        lastSwingTime = time;
        boolean wasSwinging = lastSwinging;
        lastSwinging = player.swinging;
        if (!player.swinging) {
            return -1;
        }
        // A new swing is one that either was not running a tick ago, or was running and has
        // restarted -- which shows as swingTime having gone down rather than up, since
        // updateSwingTime only ever increments it while a swing continues. Both cases are needed:
        // the first catches a swing after a pause, the second catches the next swing in a stream
        // of them, where swinging never returns to false in between.
        if (wasSwinging && time > previous) {
            return -1;
        }
        return player.swingingArm == net.minecraft.world.InteractionHand.OFF_HAND
                ? net.minecraft.network.protocol.game.ClientboundAnimatePacket.SWING_OFF_HAND
                : net.minecraft.network.protocol.game.ClientboundAnimatePacket.SWING_MAIN_HAND;
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
            case SpectateProtocol.TYPE_PEER_POSITIONS -> {
                frame.get(); // type
                if (frame.remaining() >= 2) {
                    int count = frame.getShort() & 0xFFFF;
                    count = Math.min(count, SpectateProtocol.MAX_PEER_POSITIONS);
                    List<double[]> peers = new ArrayList<>(count);
                    for (int i = 0; i < count && frame.remaining() >= 4 + 8 * 3 + 4 * 3 + 1; i++) {
                        peers.add(new double[] {
                                frame.getInt(), frame.getDouble(), frame.getDouble(),
                                frame.getDouble(), frame.getFloat(), frame.getFloat(),
                                frame.getFloat(), frame.get()});
                    }
                    ViewerSession active = viewing;
                    if (active != null) {
                        pendingMainThread.add(() -> active.applyPeerPositions(peers));
                    }
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
                    // Optional tail, so a target running an older build still centres the cache.
                    int entityId = -1;
                    float headYaw = yaw;
                    boolean onGround = true;
                    int swingAction = -1;
                    if (frame.remaining() >= 4 + 4 + 1) {
                        entityId = frame.getInt();
                        headYaw = frame.getFloat();
                        onGround = frame.get() != 0;
                    }
                    if (frame.remaining() >= 1) {
                        swingAction = frame.get();
                    }
                    ViewerSession session = viewing;
                    if (session != null) {
                        session.applyTargetPosition(x, y, z, yaw, pitch,
                                entityId, headYaw, onGround, swingAction);
                    }
                }
            }
            case SpectateProtocol.TYPE_STREAM_END -> pendingMainThread.add(this::discardViewing);
            case SpectateProtocol.TYPE_CAPTURE_START -> {
                UUID viewer = Frames.readUuid(frame);
                pendingMainThread.add(() -> beginCapture(viewer));
            }
            case SpectateProtocol.TYPE_CHUNK_REQUEST -> {
                frame.get(); // type
                if (frame.remaining() >= 2) {
                    int count = frame.getShort() & 0xFFFF;
                    count = Math.min(count, SpectateProtocol.MAX_CHUNK_REQUESTS);
                    List<net.minecraft.world.level.ChunkPos> wanted = new ArrayList<>(count);
                    for (int i = 0; i < count && frame.remaining() >= 8; i++) {
                        wanted.add(new net.minecraft.world.level.ChunkPos(
                                frame.getInt(), frame.getInt()));
                    }
                    // Rebuilding a chunk reads the level and the light engine, so it has to run on
                    // the client thread rather than here on the socket's.
                    pendingMainThread.add(() -> answerChunkRequest(wanted));
                }
            }
            case SpectateProtocol.TYPE_RESWEEP_REQUEST ->
                    pendingMainThread.add(this::answerResweepRequest);
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
            session = new CaptureSession(this::queueCaptured);
            CaptureHooks.setSession(session);
            reportedSweepDone = false;
            // A fresh viewer holds no terrain, whatever the last one was sent.
            forgetSentTerrain();
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
        announceViewer(viewer, "chat.spectate_started");
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
            forgetSentTerrain();
            // Queued frames belong to the viewer that just left. Left in place they would be
            // delivered to the next one ahead of its own opening sweep -- stale entity spawns
            // among them -- and would spend its frame budget re-sending terrain it is about to
            // be sent properly.
            terrainBacklog.clear();
            priorityBacklog.clear();
            SpectateClientSocket s = socket;
            if (s != null) {
                s.sendStreamEnd();
            }
        }
        SpectateClient.LOGGER.info("spectate: stopped streaming to {}", viewer);
        announceViewer(viewer, "chat.spectate_stopped");
    }

    /**
     * Sends the tab list again once a profile's textures have been fetched.
     *
     * <p>The first tab list a viewer gets is built the moment it starts watching, and the texture
     * fetch behind it is a network call that cannot be waited for on the client thread. So that
     * first list goes out with whatever was already cached -- for a first-ever session, nothing --
     * and without this the skin would stay default until the viewer reconnected.
     *
     * <p>A resend is cheap and idempotent: ADD_PLAYER for a player the client already knows
     * updates the existing entry, which is how a real server corrects one.
     */
    private void resendTabListIfTexturesArrived() {
        CaptureSession session = CaptureHooks.session();
        if (session == null || !session.hasViewers()) {
            return;
        }
        if (!TabList.resolvedSinceLastSend()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener listener = mc.getConnection();
        if (listener == null) {
            return;
        }
        var tabList = TabList.of(mc);
        if (tabList == null) {
            return;
        }
        try {
            StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec =
                    PacketWire.gameCodec(listener.registryAccess());
            // Removed before being re-added, which is the whole reason the previous attempt at
            // this did nothing. handlePlayerInfoUpdate stores a new PlayerInfo with
            // Map.putIfAbsent, so an ADD_PLAYER for a player the client already knows is
            // discarded and the profile is never replaced. PlayerInfo also builds its skin
            // lookup once, lazily, and caches it for the object's lifetime -- so even a replaced
            // profile would not change a skin already resolved. Only a new PlayerInfo picks up
            // new textures, and only a remove makes the client build one.
            var ids = tabList.entries().stream()
                    .map(net.minecraft.network.protocol.game
                            .ClientboundPlayerInfoUpdatePacket.Entry::profileId)
                    .toList();
            queueAhead(PacketWire.encode(codec,
                    new net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket(
                            ids)));
            queueAhead(PacketWire.encode(codec, tabList));
            SpectateClient.LOGGER.debug(
                    "spectate: resent the tab list with resolved skins for {} player(s)",
                    ids.size());
        } catch (RuntimeException e) {
            SpectateClient.LOGGER.warn("spectate: could not resend the tab list", e);
        }
    }


    /** Reports this client's position immediately, outside the usual 5 Hz cadence. */
    private void sendPositionNow(SpectateClientSocket s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        s.sendTargetPosition(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                mc.player.getYRot(), mc.player.getXRot(),
                mc.player.getId(), mc.player.getYHeadRot(), mc.player.onGround(), -1);
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
        // The tab list first, ahead of the terrain rather than between it and the entities.
        // createEntityFromPacket returns null for a player with no tab-list entry and discards
        // the spawn entirely, so the entry has to arrive before any player spawn -- and spawns
        // now include live ones, which share this queue and can arrive while the sweep's several
        // hundred chunk frames are still draining. Queued behind the terrain, the entry could be
        // seconds late and every player who moved into view in the meantime was dropped. It is
        // one small frame; nothing is gained by making it wait.
        var tabList = TabList.of(mc);
        if (tabList != null) {
            try {
                queueAhead(PacketWire.encode(codec, tabList));
            } catch (RuntimeException e) {
                SpectateClient.LOGGER.warn("spectate: could not encode the tab list", e);
            }
        } else {
            SpectateClient.LOGGER.warn("spectate: no tab list to send; players will not render");
        }
        int chunks = 0;
        for (var chunk : ChunkSweep.loadedChunksAround(mc)) {
            try {
                terrainBacklog.add(PacketWire.encode(codec, chunk));
                // Recorded so the trailing sweep does not immediately resend the whole opening
                // sweep as its first act.
                sentChunks.add(chunkKey(chunk.getX(), chunk.getZ()));
                chunks++;
            } catch (RuntimeException e) {
                SpectateClient.LOGGER.warn("spectate: could not encode a packed chunk", e);
            }
        }
        // Entities after terrain: the client drops an entity whose chunk it does not have.
        int entities = 0;
        for (var packet : ChunkSweep.entitiesAround(mc)) {
            try {
                @SuppressWarnings("unchecked")
                Packet<? super ClientGamePacketListener> game =
                        (Packet<? super ClientGamePacketListener>) packet;
                terrainBacklog.add(PacketWire.encode(codec, game));
                entities++;
            } catch (RuntimeException e) {
                SpectateClient.LOGGER.warn("spectate: could not encode a packed entity", e);
            }
        }
    }

    /** The chunk the target was in when terrain was last swept for viewers. */
    private volatile int sweptCentreX = Integer.MIN_VALUE;
    private volatile int sweptCentreZ = Integer.MIN_VALUE;
    /**
     * Chunks already sent to viewers, so each is sent once.
     *
     * <p>Concurrent, and it has to be: {@link #sweepNewTerrain} reads and writes this on the
     * client thread while {@link #queueCaptured} writes it from Netty's IO thread, which is where
     * {@code channelRead0} delivers captured packets. With a plain HashSet the two collided
     * during a resize and the set was left corrupt -- an entry in the wrong bucket reads back as
     * "already sent" for a chunk that never was, so the sweep skipped it and nothing ever looked
     * at it again. That is a permanent hole with a clean edge, appearing only when the threads
     * happened to collide.
     */
    private final java.util.Set<Long> sentChunks = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Sends viewers the chunks the target has walked into. On the client thread.
     *
     * <p>This is what replaces biasing the capture region towards the target's travel. The problem
     * that bias was trying to solve is real: a server dispatches chunks at the player's full view
     * distance, so terrain ahead of a walking target arrives long before the target is near it and
     * is rejected by a radius test centred on where it stands. But deforming the region only moved
     * the hole -- pushing the box forwards lost exactly as much terrain to the sides and behind as
     * it gained ahead.
     *
     * <p>The chunks are not lost, though: the target's own client keeps them, and by the time the
     * target walks far enough for them to be inside the capture radius they are sitting in its
     * cache. So rather than trying to catch them in flight, they are swept up afterwards. A client
     * cannot ask its server to resend a chunk, but it can rebuild a packet for any chunk it holds,
     * which is what the opening sweep already does.
     *
     * <p>Only on a chunk boundary, and only the chunks not already sent. A session that walks in a
     * straight line sends one new edge per chunk crossed rather than re-sending the whole region.
     */
    private void sweepNewTerrain() {
        CaptureSession session = CaptureHooks.session();
        SpectateClientSocket s = socket;
        if (session == null || !session.hasViewers() || s == null) {
            // Forgotten when nobody is watching, so the next session starts from a clean slate
            // rather than believing it has already sent terrain to a viewer that never saw it.
            if (!sentChunks.isEmpty()) {
                forgetSentTerrain();
            }
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener listener = mc.getConnection();
        if (mc.player == null || mc.level == null || listener == null) {
            return;
        }
        var centre = mc.player.chunkPosition();
        if (centre.x() == sweptCentreX && centre.z() == sweptCentreZ) {
            return;
        }
        sweptCentreX = centre.x();
        sweptCentreZ = centre.z();
        // Checked before anything is marked. An earlier version marked the whole region and
        // then returned here, which wrote off a 29x29 block of terrain as "sent" without sending
        // any of it -- and because a mark is permanent, every one of those chunks was lost for
        // the rest of the session. One deep-backlog moment was enough to leave the viewer with
        // almost nothing, which is exactly what it did.
        //
        // Returning before the sweep marks anything means the same region is simply reconsidered
        // at the next chunk boundary, when the backlog has had a chance to drain.
        if (terrainBacklog.size() > TERRAIN_FRAMES_PER_TICK * 8) {
            // Not recorded as swept, so the next boundary retries rather than skipping ahead.
            sweptCentreX = Integer.MIN_VALUE;
            sweptCentreZ = Integer.MIN_VALUE;
            return;
        }
        int r = SpectateProtocol.CAPTURE_RADIUS_CHUNKS;
        List<net.minecraft.world.level.ChunkPos> wanted = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = centre.x() + dx;
                int z = centre.z() + dz;
                if (!sentChunks.contains(chunkKey(x, z))) {
                    wanted.add(new net.minecraft.world.level.ChunkPos(x, z));
                }
            }
        }
        if (wanted.isEmpty()) {
            return;
        }
        StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec =
                PacketWire.gameCodec(listener.registryAccess());
        int sent = 0;
        // Marked here, as each chunk is actually queued, rather than for the whole region up
        // front. Marking up front was an attempt to stop chunks this client does not hold from
        // being reconsidered every boundary, but it marked unsendable and unsent chunks alike and
        // so threw away terrain permanently. The flood it was guarding against is bounded by the
        // backlog check above and by chunksAt only returning chunks that are actually loaded.
        for (var chunk : ChunkSweep.chunksAt(mc, wanted)) {
            try {
                terrainBacklog.add(PacketWire.encode(codec, chunk));
                sentChunks.add(chunkKey(chunk.getX(), chunk.getZ()));
                sent++;
            } catch (RuntimeException e) {
                SpectateClient.LOGGER.warn("spectate: could not encode a trailing chunk", e);
            }
        }
        // Entries far outside the region are dropped, so a long session does not keep one per
        // chunk it ever passed through. Generously beyond the capture radius: an entry dropped
        // while its chunk could still come back into range would have that chunk reconsidered,
        // and reconsidering in bulk is what made an earlier version of this flood the backlog.
        int forget = r * 3;
        sentChunks.removeIf(key -> Math.abs((int) (key >> 32) - centre.x()) > forget
                || Math.abs((int) (long) key - centre.z()) > forget);
        if (sent > 0) {
            SpectateClient.LOGGER.debug("spectate: swept {} newly loaded chunk(s) at {},{}",
                    sent, centre.x(), centre.z());
        }
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * Asks the target to send its surroundings again from scratch.
     *
     * <p>For a viewer that has arrived somewhere with no terrain at all, which the repair loop is
     * deliberately not built to fix; see {@link SpectateProtocol#TYPE_RESWEEP_REQUEST}.
     */
    public void requestResweep() {
        SpectateClientSocket s = socket;
        if (s != null && viewing != null) {
            s.sendResweepRequest();
        }
    }

    /**
     * Asks the target to resend any chunks this viewer is missing. On the client thread.
     *
     * <p>Rate limiting lives in {@link ChunkRepair}, which returns an empty list on the ticks it
     * does not sweep, so this runs every tick and sends on few of them.
     */
    private void requestMissingChunks(ViewerSession session) {
        SpectateClientSocket s = socket;
        if (s == null) {
            return;
        }
        var missing = session.missingChunks();
        if (!missing.isEmpty()) {
            s.sendChunkRequest(missing);
        }
    }

    /**
     * Answers a viewer that has no terrain where it is, by sweeping again. On the client thread.
     *
     * <p>Reuses the session's own opening sweep rather than a second code path: that is already
     * the operation "send everything around the target", it rebuilds from this client's cache
     * without involving the server, and it queues the tab list ahead of the terrain, which a
     * viewer that has just refocused needs for the same reason a joining one does.
     */
    private void answerResweepRequest() {
        CaptureSession session = CaptureHooks.session();
        SpectateClientSocket s = socket;
        if (session == null || s == null) {
            return;
        }
        // Forgotten first: the viewer is asking precisely because it no longer has the terrain,
        // so what was sent before is not what it holds, and sendLoadedTerrain would otherwise be
        // free to skip nothing while the trailing sweep skipped everything.
        forgetSentTerrain();
        sendLoadedTerrain(s, session);
    }

    /**
     * Answers a viewer that asked for chunks it is missing. On the client thread.
     *
     * <p>Queued ahead of the ordinary terrain backlog. A repair answers a hole the viewer is
     * looking at now, and the backlog can be hundreds of frames deep while a session is catching
     * up -- putting the answer behind all of it would make the repair arrive long after the viewer
     * asked again, which is how a repair loop turns into a request loop.
     *
     * <p>Not gated on the requesting viewer's identity beyond what the service already enforces:
     * it forwards a chunk request only from a viewer the target has accepted, and the payload
     * names chunks rather than a recipient, so the worst a viewer can do is ask for terrain within
     * the radius it is already being sent.
     */
    private void answerChunkRequest(List<net.minecraft.world.level.ChunkPos> wanted) {
        CaptureSession session = CaptureHooks.session();
        SpectateClientSocket s = socket;
        if (session == null || s == null || wanted.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener listener = mc.getConnection();
        if (listener == null) {
            return;
        }
        StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec =
                PacketWire.gameCodec(listener.registryAccess());
        for (var chunk : ChunkSweep.chunksAt(mc, wanted)) {
            try {
                queueAhead(PacketWire.encode(codec, chunk));
            } catch (RuntimeException e) {
                SpectateClient.LOGGER.warn("spectate: could not encode a repaired chunk", e);
            }
        }
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
        SpectateClient.LOGGER.debug("spectate: sending handshake -- {} ({} config frames, login={}B)",
                recorder.describe(), configuration.size(),
                login == null ? 0 : login.length);
        return new EncodedHandshake(configuration, login);
    }

    /**
     * Tells this player in chat that a viewer started or stopped watching them.
     *
     * <p>Said once, in chat, rather than only shown in the corner: the HUD indicator renders
     * from a viewer count and so had no name to show, and a line in chat is also the only form
     * of this that survives being looked away from.
     */
    private void announceViewer(UUID viewer, String key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        mc.player.sendSystemMessage(SpectateLang.text(key, viewerName(mc, viewer)).withColor(CHAT_COLOUR));
    }

    /**
     * The best name available for a viewer.
     *
     * <p>The server's player list first -- someone watching this client is almost always on the
     * same server -- then the service roster, which carries verified names but lists only
     * players who themselves consent to being watched. A viewer with spectating turned off
     * appears in neither, so the id is the last resort: naming someone imperfectly is better
     * than a message that names nobody.
     */
    private static String viewerName(Minecraft mc, UUID viewer) {
        if (mc.getConnection() != null) {
            var entry = mc.getConnection().getPlayerInfo(viewer);
            if (entry != null && entry.getProfile() != null
                    && entry.getProfile().name() != null) {
                return entry.getProfile().name();
            }
        }
        for (SpectateRoster.Peer peer : SpectateRoster.peers()) {
            if (viewer.equals(peer.id())) {
                return peer.display();
            }
        }
        return viewer.toString().substring(0, 8);
    }

    /**
     * Forgets what terrain a viewer has been sent.
     *
     * <p>Called when a capture starts and when one ends, rather than left to
     * {@link #sweepNewTerrain()} noticing later that no session exists: {@link #tick()} drains
     * every queued main-thread task before that check runs, so a stop and a start arriving
     * together never present a session-less moment and the new session would inherit the old
     * one's record. A chunk is sent once, so an inherited record is terrain the new viewer
     * never receives.
     */
    private void forgetSentTerrain() {
        sentChunks.clear();
        sweptCentreX = Integer.MIN_VALUE;
        sweptCentreZ = Integer.MIN_VALUE;
    }

    /**
     * Reports the players near the target, once per tick.
     *
     * <p>The target's own entity is synthesised every tick because a client is never told where
     * it is. Everyone else was left to the server's own movement packets, which are throttled to
     * every few ticks and further with distance -- so the target moved smoothly in a viewer's
     * world while every other player stuttered, which reads as lag although nothing is delayed.
     * The target's client holds these players already interpolated, so the same treatment is
     * applied to them.
     *
     * <p>Players only, and only inside the swept region. Mobs keep the server's rate: they are
     * rarely what a viewer is watching, and including them would make this frame unbounded next
     * to a farm.
     */
    private void reportPeerPositions() {
        CaptureSession session = CaptureHooks.session();
        if (session == null || !session.hasViewers()) {
            return;
        }
        SpectateClientSocket s = socket;
        if (s == null || !s.isAuthenticated()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        double reach = SpectateProtocol.SWEEP_RADIUS_CHUNKS * 16.0;
        double maxSqr = reach * reach;
        List<double[]> peers = new ArrayList<>();
        for (var entity : mc.level.players()) {
            if (entity == mc.player) {
                // The target reports itself through sendTargetPosition, which also carries the
                // swing it would otherwise never send.
                continue;
            }
            if (entity.distanceToSqr(mc.player.position()) > maxSqr) {
                continue;
            }
            peers.add(new double[] {
                    entity.getId(), entity.getX(), entity.getY(), entity.getZ(),
                    entity.getYRot(), entity.getXRot(), entity.getYHeadRot(),
                    entity.onGround() ? 1 : 0});
            if (peers.size() >= SpectateProtocol.MAX_PEER_POSITIONS) {
                break;
            }
        }
        s.sendPeerPositions(peers);
    }
}
