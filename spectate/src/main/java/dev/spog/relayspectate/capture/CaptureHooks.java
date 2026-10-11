package dev.spog.relayspectate.capture;

import net.minecraft.network.protocol.Packet;

/**
 * The static entry point the capture mixin calls, and the owner of this client's capture state.
 *
 * <p>Static because a mixin has no clean way to reach an injected instance, and because there is
 * exactly one of each of these per client: one connection to the target's own server, one handshake
 * recording of it, one set of viewers watching.
 *
 * <p>Everything here runs on a Netty event loop, on the target's own connection. That is the
 * constraint that shapes it: the work on the common path -- no viewers -- must be a volatile read
 * and a return, because this runs for every packet the target's client receives whether or not
 * anyone is watching.
 */
public final class CaptureHooks {
    private CaptureHooks() {
    }

    private static final HandshakeRecorder HANDSHAKE = new HandshakeRecorder();

    /** Set when a session is streaming; null the rest of the time, which is the fast path. */
    private static volatile CaptureSession session;

    public static HandshakeRecorder handshake() {
        return HANDSHAKE;
    }

    /** The live session, or null if nobody is watching. */
    public static CaptureSession session() {
        return session;
    }

    public static void setSession(CaptureSession newSession) {
        session = newSession;
    }

    /** True if at least one viewer is being streamed to right now. */
    public static boolean isStreaming() {
        CaptureSession s = session;
        return s != null && s.hasViewers();
    }

    /**
     * Called for every packet this client receives from its own server.
     *
     * <p>The handshake recording happens unconditionally: those packets arrive during login, long
     * before any viewer asks, and are never sent again. Recording them always costs a list append a
     * few dozen times per connection. The live forwarding happens only while a session is open.
     */
    public static void onInboundPacket(Packet<?> packet) {
        // Bundles are unwrapped here, before either consumer sees them. A bundle is a container
        // the server uses to group packets that must be applied in the same tick -- an entity's
        // spawn together with the entity data that gives it meaning, a batch of chunks -- and
        // channelRead0 delivers the bundle rather than its contents. Neither the handshake
        // recorder nor the capture filter can match on what is inside one, so everything bundled
        // was dropped: live-dropped items appeared as nothing, and chunks loaded while walking
        // never reached a viewer at all, while the same entities and chunks swept from the level
        // at session start were fine.
        if (packet instanceof net.minecraft.network.protocol.BundlePacket<?> bundle) {
            for (Packet<?> sub : bundle.subPackets()) {
                onInboundPacket(sub);
            }
            return;
        }
        if (HandshakeRecorder.isHandshake(packet)) {
            HANDSHAKE.offer(packet);
        }
        CaptureSession s = session;
        if (s != null) {
            s.offer(packet);
        }
    }

    /**
     * True while a packet from the target's own server is being applied to its client.
     *
     * <p>Needed because the local-effect hooks sit on the same methods the inbound handlers call:
     * {@code handleLevelEvent} calls {@code ClientLevel.levelEvent} and {@code handleSoundEvent}
     * calls {@code playSeededSound}. Without this, every sound and level event the server sent
     * would be captured on its way through and forwarded a second time, so a viewer would hear
     * each one twice -- once from the forwarded original and once from the capture.
     *
     * <p>A thread local rather than a field: inbound packets are applied on the client thread,
     * which is also where local effects are raised, so the two cannot be told apart by thread.
     */
    private static final ThreadLocal<Boolean> APPLYING_INBOUND =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Marks the start of applying an inbound packet, so effects it causes are not re-captured. */
    public static void beginApplyingInbound() {
        APPLYING_INBOUND.set(Boolean.TRUE);
    }

    /** Marks the end of applying an inbound packet. */
    public static void endApplyingInbound() {
        APPLYING_INBOUND.set(Boolean.FALSE);
    }

    /**
     * Called when the target's client raises a level event itself.
     *
     * <p>A block break is the case that matters. The server broadcasts it to every player
     * <em>except</em> the one who broke the block -- see
     * {@code ServerLevel.levelEvent(Entity except, ...)} -- because that client predicted the
     * break locally and produced the particles and sound for itself. So the target's own breaking
     * is absent from its inbound stream, and a viewer saw blocks vanish silently.
     */
    public static void onLocalLevelEvent(int type, net.minecraft.core.BlockPos pos, int data) {
        if (APPLYING_INBOUND.get()) {
            return;
        }
        CaptureSession s = session;
        if (s == null) {
            return;
        }
        s.offer(new net.minecraft.network.protocol.game.ClientboundLevelEventPacket(
                type, pos, data, false));
    }

    /**
     * Called when the target's client plays a sound itself.
     *
     * <p>The same exclusion as {@link #onLocalLevelEvent}: a client-predicted sound, the block
     * break's among them, is played locally and never sent to the client that caused it. The seed
     * is passed through so a viewer hears the same variant of a multi-variant sound.
     */
    public static void onLocalSound(net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound,
                                    net.minecraft.sounds.SoundSource category,
                                    double x, double y, double z,
                                    float volume, float pitch, long seed) {
        if (APPLYING_INBOUND.get()) {
            return;
        }
        CaptureSession s = session;
        if (s == null) {
            return;
        }
        s.offer(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                sound, category, x, y, z, volume, pitch, seed));
    }

    /** Called when the target's own connection drops: forget everything about it. */
    public static void onDisconnect() {
        HANDSHAKE.clear();
        CaptureSession s = session;
        if (s != null) {
            s.clear();
        }
        session = null;
    }
}
