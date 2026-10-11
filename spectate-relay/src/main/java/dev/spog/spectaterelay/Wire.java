package dev.spog.spectaterelay;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The spectate wire format, service side.
 *
 * <p>Deliberately a copy of the mod's {@code SpectateProtocol} and {@code Frames} rather than a
 * shared dependency. The alternative would make this service depend on the mod module, which
 * depends on Minecraft and Fabric Loom -- pulling the game onto a headless VPS build for the sake
 * of about forty constants. The two must be kept in step by hand; the constants are append-only and
 * the version byte exists to catch a mismatch.
 *
 * <p>Keep in sync with {@code dev.spog.relayspectate.net.SpectateProtocol}.
 */
public final class Wire {
    private Wire() {
    }

    public static final int VERSION = 1;

    // viewer -> relay
    public static final byte TYPE_SESSION_REQUEST = 0x01;
    public static final byte TYPE_SESSION_STOP = 0x02;

    // relay -> viewer
    public static final byte TYPE_SESSION_READY = 0x10;
    public static final byte TYPE_SESSION_DENIED = 0x11;

    public static final byte DENY_NOT_TRUSTED = 0x00;
    public static final byte DENY_NO_ADDON = 0x01;
    public static final byte DENY_OFFLINE = 0x02;
    public static final byte DENY_REVOKED = 0x03;
    public static final byte DENY_BUSY = 0x04;

    // target -> relay -> viewer
    public static final byte TYPE_HANDSHAKE = 0x20;
    public static final byte TYPE_GAME_PACKET = 0x21;
    public static final byte TYPE_STREAM_END = 0x22;
    public static final byte TYPE_LOGIN_PACKET = 0x23;
    public static final byte TYPE_TARGET_POSITION = 0x24;
    public static final byte TYPE_CAPTURE_UNAVAILABLE = 0x25;

    // relay -> target
    public static final byte TYPE_CAPTURE_START = 0x30;
    public static final byte TYPE_CAPTURE_STOP = 0x31;

    /** A frame's type byte without consuming it, or -1 if empty. */
    public static byte typeOf(ByteBuffer frame) {
        return frame.remaining() < 1 ? -1 : frame.get(frame.position());
    }

    public static ByteBuffer of(byte type) {
        return ByteBuffer.wrap(new byte[] {type});
    }

    public static ByteBuffer of(byte type, UUID id) {
        ByteBuffer buf = ByteBuffer.allocate(1 + 16);
        buf.put(type);
        buf.putLong(id.getMostSignificantBits());
        buf.putLong(id.getLeastSignificantBits());
        return buf.flip();
    }

    public static ByteBuffer deny(byte reason, String detail) {
        byte[] text = detail == null ? new byte[0] : detail.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + text.length);
        buf.put(TYPE_SESSION_DENIED).put(reason).put(text);
        return buf.flip();
    }

    /**
     * Reads a UUID following the type byte, or null if the frame is too short.
     *
     * <p>Returns null rather than throwing: a peer's frames are untrusted input, and a malformed
     * one should be dropped, not propagate an exception onto a socket thread.
     */
    public static UUID readUuid(ByteBuffer frame) {
        if (frame.remaining() < 1 + 16) {
            return null;
        }
        frame.get();
        return new UUID(frame.getLong(), frame.getLong());
    }
}
