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
        if (HandshakeRecorder.isHandshake(packet)) {
            HANDSHAKE.offer(packet);
        }
        CaptureSession s = session;
        if (s != null) {
            s.offer(packet);
        }
    }

    /**
     * Called when the target's client spawns a particle locally.
     *
     * <p>Client-side particles -- block cracks, footfalls, splashes -- are computed by the client
     * from state it already has and are never sent to or from a server. A viewer is fed a packet
     * stream rather than simulating, so it has nothing to compute them from and shows none of
     * them. This turns each one into the packet a server would have sent for it, and hands it to
     * the ordinary capture path, which applies the usual radius filter and encoding.
     *
     * <p>Sent with a count of zero and the offsets as the particle's own velocity, which is how a
     * server encodes a single particle at an exact position with an exact motion: a non-zero count
     * instead means "this many, scattered randomly over these distances", which would turn one
     * crack particle into a cloud.
     */
    public static void onLocalParticle(net.minecraft.core.particles.ParticleOptions options,
                                       boolean overrideLimiter, boolean alwaysRender,
                                       double x, double y, double z,
                                       double dx, double dy, double dz) {
        CaptureSession s = session;
        if (s == null) {
            return;
        }
        s.offer(new net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket(
                options, overrideLimiter, alwaysRender, x, y, z,
                (float) dx, (float) dy, (float) dz, 0.0F, 0));
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
