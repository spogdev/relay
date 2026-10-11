package dev.spog.relayspectate.net;

/**
 * The wire format for a spectate session: binary frames on a WebSocket channel separate from
 * Relay's own.
 *
 * <p>Why a second channel rather than v4 messages on Relay's existing one. Relay speaks JSON text
 * frames, one object per frame, and the mod serializes all of its sends through a single chain
 * (the JDK WebSocket forbids overlapping sends). Chunk data is binary and arrives in multi-hundred-
 * kilobyte bursts; base64'ing it into JSON would cost a third more bytes, and queueing it on that
 * one chain would put attack pings -- the most latency-sensitive thing Relay does -- behind a
 * terrain burst. A separate socket keeps the two from competing, and lets this one be dropped
 * entirely when no session is running.
 *
 * <p>Frame layout. Every frame is a binary WebSocket message:
 * <pre>
 *   byte   type        one of the TYPE_ constants
 *   ...    payload     type-specific, see each constant
 * </pre>
 * There is no length prefix: WebSocket already delimits messages, and the relay forwards each one
 * whole. Payloads that carry Minecraft packets hold them in the game's own wire encoding, so the
 * host side can hand them to the vanilla decoder untouched -- this mod never parses a game packet,
 * which is what keeps it from needing to track packet-format churn between versions.
 */
public final class SpectateProtocol {
    private SpectateProtocol() {
    }

    /**
     * The spectate channel's own version, independent of Relay's {@code PROTOCOL_VERSION}. Relay's
     * protocol is untouched by this addon; the only thing the two share is the relay host.
     *
     * <p>v1 -- sessions, handshake replay, live game packets, viewer input.
     */
    public static final int VERSION = 1;

    /** The URL path the relay serves this channel on. */
    public static final String PATH = "/spectate";

    // ---- viewer -> relay ----

    /**
     * Open a session: {@code [16-byte target UUID]}. The relay checks that the target trusts the
     * viewer and has the addon, then asks the target to start capturing. Answered with
     * {@link #TYPE_SESSION_READY} or {@link #TYPE_SESSION_DENIED}.
     */
    public static final byte TYPE_SESSION_REQUEST = 0x01;

    /** Close the session the sender holds. No payload. */
    public static final byte TYPE_SESSION_STOP = 0x02;

    // ---- relay -> viewer ----

    /**
     * The target accepted and is about to stream: {@code [16-byte target UUID]}. The viewer builds
     * its host and waits for {@link #TYPE_HANDSHAKE} before showing a world.
     */
    public static final byte TYPE_SESSION_READY = 0x10;

    /**
     * The session will not start: {@code [1-byte reason][UTF-8 detail]}. Reasons are
     * {@link #DENY_NOT_TRUSTED}, {@link #DENY_NO_ADDON}, {@link #DENY_OFFLINE},
     * {@link #DENY_REVOKED}, {@link #DENY_BUSY}.
     */
    public static final byte TYPE_SESSION_DENIED = 0x11;

    public static final byte DENY_NOT_TRUSTED = 0x00;
    public static final byte DENY_NO_ADDON = 0x01;
    public static final byte DENY_OFFLINE = 0x02;
    public static final byte DENY_REVOKED = 0x03;
    public static final byte DENY_BUSY = 0x04;

    // ---- target -> relay -> viewer ----

    /**
     * The static setup the target's own server sent it at login: registries, tags, dimension
     * codecs, biomes. Payload is {@code [varint count][packet]...}, each packet being
     * {@code [varint id][bytes]} in the game's wire encoding.
     *
     * <p>Sent once per session, before any {@link #TYPE_GAME_PACKET}. This is what lets the viewer
     * render the target's world correctly on a modded or datapacked server: the definitions come
     * from the server the target is actually on, rather than being guessed from the viewer's own
     * built-in registries.
     */
    public static final byte TYPE_HANDSHAKE = 0x20;

    /**
     * One live game packet, forwarded as it arrives: {@code [varint id][bytes]}. Chunk loads, block
     * changes, entity spawns and movement. This is the continuous part of the stream.
     */
    public static final byte TYPE_GAME_PACKET = 0x21;

    /**
     * The target's login packet, which is a game-phase packet but belongs to the handshake rather
     * than the live stream: {@code [bytes]}.
     *
     * <p>Separate from {@link #TYPE_HANDSHAKE} because the two are encoded with different codecs.
     * Configuration-phase packets use a plain buffer and the configuration packet-id table; the
     * login packet uses the game table and needs the registries the configuration just established.
     * Sending both under one type would leave the receiver guessing which codec to decode with, and
     * guessing wrong fails with an unhelpful unknown-packet error rather than anything that names
     * the cause.
     */
    public static final byte TYPE_LOGIN_PACKET = 0x23;

    /** The target stopped sharing (revoked, disconnected, or left the radius). No payload. */
    public static final byte TYPE_STREAM_END = 0x22;

    /**
     * Where the target is, so the viewer can follow it:
     * {@code [double x][double y][double z][float yaw][float pitch]}.
     *
     * <p>Sent on a timer rather than derived from the forwarded stream. The target's own position
     * does not arrive in its inbound packets at all -- a client knows where it is because it moved
     * itself there, and the server only corrects it occasionally -- so there is nothing in the
     * relayed stream to read it from.
     *
     * <p>The viewer needs it for two separate reasons: to place the camera, and to set the client
     * chunk cache's view centre. The second is not optional. {@code ClientChunkCache} discards any
     * chunk outside its centre, and the centre starts at 0,0, so without this every forwarded
     * chunk is dropped on arrival and the viewer sees an empty world however much terrain is sent.
     */
    public static final byte TYPE_TARGET_POSITION = 0x24;

    /**
     * The target has no world to share -- it is in a menu or between servers. Answered to a
     * capture request so the service can deny the session rather than leaving the viewer waiting
     * for a handshake that will never come. No payload.
     */
    public static final byte TYPE_CAPTURE_UNAVAILABLE = 0x25;

    // ---- relay -> target ----

    /**
     * Begin capturing for a viewer: {@code [16-byte viewer UUID]}. The target's client replies by
     * sending {@link #TYPE_HANDSHAKE} then streaming.
     */
    public static final byte TYPE_CAPTURE_START = 0x30;

    /** Stop capturing for a viewer: {@code [16-byte viewer UUID]}. */
    public static final byte TYPE_CAPTURE_STOP = 0x31;

    /**
     * How far from the target, in chunks, the capture forwards terrain. Deliberately well under a
     * typical server view distance: chunk volume grows with the square of the radius, so trimming
     * 16 to 10 is roughly a 60% cut in both the initial burst and the steady-state rate. The viewer
     * sees less of the distance than the target does, which is the intended trade -- the camera is
     * locked to the target, so the far edge is rarely what is being looked at.
     */
    public static final int CAPTURE_RADIUS_CHUNKS = 10;
}
