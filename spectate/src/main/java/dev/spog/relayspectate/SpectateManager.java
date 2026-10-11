package dev.spog.relayspectate;

import dev.spog.relayspectate.capture.CaptureHooks;
import dev.spog.relayspectate.capture.CaptureSession;
import dev.spog.relayspectate.capture.ChunkSweep;
import dev.spog.relayspectate.capture.HandshakeRecorder;
import dev.spog.relayspectate.capture.PacketWire;
import dev.spog.relayspectate.capture.TabList;
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
    private int captureTicks;
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

    /**
     * Inbound frame counts by type, for one summary line per session.
     *
     * <p>Worth the few lines: the loss between "the target sent 421 chunks" and "the viewer
     * rendered nothing" has been chased across several rounds by comparing two logs that each
     * describe only their own end, and the service in between is not visible in either. Counting
     * what actually arrived, by type, turns that into one line.
     */
    private final java.util.concurrent.ConcurrentHashMap<Byte, java.util.concurrent.atomic.AtomicInteger>
            inboundFrames = new java.util.concurrent.ConcurrentHashMap<>();

    private void countInboundFrame(byte type) {
        inboundFrames.computeIfAbsent(type,
                ignored -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
    }

    private String inboundFrameSummary() {
        StringBuilder out = new StringBuilder();
        inboundFrames.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> out.append(out.isEmpty() ? "" : " ")
                        .append(String.format("0x%02x", e.getKey()))
                        .append('=').append(e.getValue().get()));
        return out.isEmpty() ? "none" : out.toString();
    }
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
        drainTerrainBacklog();
        reportPositionIfStreaming();
        resendTabListIfTexturesArrived();
        reportCaptureOnce();
        ViewerSession session = viewing;
        if (session != null) {
            session.tickCamera();
            // One summary a few seconds in, so a blank world can be told apart from a world whose
            // terrain never arrived without needing a packet-by-packet log.
            // Repeated like the capture summary, and for the same reason: terrain that fails to
            // arrive as the target walks cannot be seen in a snapshot taken at five seconds.
            if (viewerTicks > 0 && viewerTicks % 300 == 0) {
                SpectateClient.LOGGER.info("spectate: viewer -- {}; {}",
                        session.chunkSummary(), session.playerSummary());
            }
            if (++viewerTicks == 100) {
                int chunks = session.chunksReceived();
                if (chunks > 0) {
                    SpectateClient.LOGGER.info(
                            "spectate: {} chunk packets delivered after 5s; "
                            + "frames received by type: {}; {}",
                            chunks, inboundFrameSummary(), session.playerSummary());
                } else {
                    // Zero is a distinct failure from "some", and the difference decides where to
                    // look. Vanilla only reports it as "Timed out while waiting for the client to
                    // load chunks" 30 seconds later, which names neither spectating nor the stage
                    // that failed -- and reading it as the fault rather than the symptom costs a
                    // round of debugging. So say which side went quiet, and whether the target
                    // ever sent a position, since terrain is sent immediately after one.
                    SpectateClient.LOGGER.warn(
                            "spectate: no terrain after 5s -- inGame={} positionSeen={}; "
                            + "frames received by type: {}",
                            session.isInGame(), session.hasSeenPosition(),
                            inboundFrameSummary());
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
            SpectateClient.LOGGER.info("spectate: finished sending swept terrain");
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
        countInboundFrame(Frames.typeOf(frame));
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
        inboundFrames.clear();
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
            captureTicks = 0;
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
            SpectateClient.LOGGER.info(
                    "spectate: resent the tab list with resolved skins for {} player(s)",
                    ids.size());
        } catch (RuntimeException e) {
            SpectateClient.LOGGER.warn("spectate: could not resend the tab list", e);
        }
    }

    /**
     * Logs one capture summary a few seconds into a session.
     *
     * <p>The counterpart to the viewer's own summary: between them they say whether a packet kind
     * the viewer needed was dropped before it ever left this client.
     */
    private void reportCaptureOnce() {
        CaptureSession session = CaptureHooks.session();
        if (session == null || !session.hasViewers()) {
            captureTicks = 0;
            return;
        }
        // Repeated, not once. The counts that matter most are about what happens as the target
        // moves -- chunks loaded while walking, items dropped mid-session -- and a single summary
        // five seconds in is taken before any of that has happened. Every 15 seconds is often
        // enough to bracket a test and rare enough not to fill the log.
        if (++captureTicks < 300) {
            return;
        }
        captureTicks = 0;
        SpectateClient.LOGGER.info("spectate: capture -- {}", session.frameSummary());
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
        SpectateClient.LOGGER.info("spectate: queued {} chunks and {} entity packets",
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
