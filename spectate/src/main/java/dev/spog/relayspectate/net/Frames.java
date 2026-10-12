package dev.spog.relayspectate.net;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Reads and writes {@link SpectateProtocol} frames. Deliberately tiny and dependency-free: the
 * payloads that matter are opaque game packets, so this only has to handle the envelope.
 *
 * <p>All multi-byte integers are big-endian, matching {@link ByteBuffer}'s default and MinecraftClient's
 * own network order.
 */
public final class Frames {
    private Frames() {
    }

    /** A frame's type byte, or -1 if the buffer is empty. */
    public static byte typeOf(ByteBuffer frame) {
        return frame.remaining() < 1 ? (byte) -1 : frame.get(frame.position());
    }

    /** A frame carrying only a type byte. */
    public static ByteBuffer of(byte type) {
        return ByteBuffer.wrap(new byte[] {type});
    }

    /** A frame carrying a type byte and one UUID. */
    public static ByteBuffer of(byte type, UUID id) {
        ByteBuffer buf = ByteBuffer.allocate(1 + 16);
        buf.put(type);
        buf.putLong(id.getMostSignificantBits());
        buf.putLong(id.getLeastSignificantBits());
        return buf.flip();
    }

    /** A frame carrying a type byte and a raw payload. */
    public static ByteBuffer of(byte type, byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(1 + payload.length);
        buf.put(type).put(payload);
        return buf.flip();
    }

    /** A deny frame: type, reason byte, UTF-8 detail. */
    public static ByteBuffer deny(byte reason, String detail) {
        byte[] text = detail == null ? new byte[0] : detail.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + 1 + text.length);
        buf.put(SpectateProtocol.TYPE_SESSION_DENIED).put(reason).put(text);
        return buf.flip();
    }

    /**
     * Reads the UUID that follows a type byte. Advances past both.
     *
     * @throws java.nio.BufferUnderflowException if the frame is too short, which the callers treat
     *     as a malformed frame and drop -- a peer is not trusted to send well-formed input.
     */
    public static UUID readUuid(ByteBuffer frame) {
        frame.get(); // type
        return new UUID(frame.getLong(), frame.getLong());
    }

    /** The bytes after the type byte, copied. Advances the buffer to its limit. */
    public static byte[] readPayload(ByteBuffer frame) {
        frame.get(); // type
        byte[] out = new byte[frame.remaining()];
        frame.get(out);
        return out;
    }

    /** The reason byte of a deny frame. */
    public static byte readDenyReason(ByteBuffer frame) {
        frame.get(); // type
        return frame.get();
    }

    /** The detail text of a deny frame, after its reason byte. */
    public static String readDenyDetail(ByteBuffer frame) {
        byte[] rest = new byte[frame.remaining()];
        frame.get(rest);
        return new String(rest, StandardCharsets.UTF_8);
    }
}
