package dev.spog.teamlocator.relay.protocol;

import java.util.List;

/**
 * The wire protocol between a TeamLocator client and the relay: one JSON object per WebSocket text
 * frame, discriminated by the {@code type} field. These types mirror the mod's former in-game
 * payloads one-for-one, so the client's routing semantics are unchanged — only the transport moved.
 *
 * <p>Identity rule: no client-supplied UUID is ever trusted for routing. The relay attributes every
 * frame to the UUID it verified during the auth handshake. UUIDs that DO appear on the wire (in
 * {@code sharingWith}, {@code blocked}, snapshot entries, ping attacker) are <em>other</em> players
 * being referenced, not the sender asserting its own identity.
 */
public final class Messages {
    private Messages() {
    }

    public static final int PROTOCOL_VERSION = 1;

    // ---- client -> relay ----

    /** First frame after the socket opens. Begins the Mojang auth handshake. */
    public static final class Hello {
        public String type = "hello";
        public int protocolVersion = PROTOCOL_VERSION;
        /** Normalized Minecraft server address the client is on; scopes who it can see. */
        public String mcServer;
        /** The account name Mojang will verify (used as the hasJoinedServer username). */
        public String profileName;
    }

    /**
     * Sent after the client has called {@code sessionService.joinServer(..., serverId)} for the
     * challenge nonce. Carries nothing secret — verification happens relay-side against Mojang.
     */
    public static final class AuthResponse {
        public String type = "auth-response";
    }

    /** The set of players this client shares its coordinates with (already filtered by the mod). */
    public static final class TrustUpdate {
        public String type = "trust-update";
        public List<String> sharingWith;
    }

    /** The set of players whose attack pings this client refuses (anti-spam). */
    public static final class BlockUpdate {
        public String type = "block-update";
        public List<String> blocked;
    }

    /** The client's own live position. The relay stamps it with the verified UUID. */
    public static final class PositionUpdate {
        public String type = "position-update";
        public double x;
        public double y;
        public double z;
        public String dimension;
    }

    /** "I'm being attacked." The relay fans it out to mutual-trust peers who haven't blocked us. */
    public static final class Ping {
        public String type = "ping";
    }

    // ---- relay -> client ----

    /** Relay's reply to {@link Hello}: the single-use nonce to pass to Mojang's joinServer. */
    public static final class AuthChallenge {
        public String type = "auth-challenge";
        public String serverId;

        public AuthChallenge(String serverId) {
            this.serverId = serverId;
        }
    }

    /** Auth succeeded; carries the verified UUID the relay will attribute this connection to. */
    public static final class AuthOk {
        public String type = "auth-ok";
        public String uuid;

        public AuthOk(String uuid) {
            this.uuid = uuid;
        }
    }

    /** Auth failed; {@code reason} is a short human-readable code. */
    public static final class AuthFail {
        public String type = "auth-fail";
        public String reason;

        public AuthFail(String reason) {
            this.reason = reason;
        }
    }

    /** Positions of everyone who shares with this viewer, in the viewer's MC-server scope. */
    public static final class PositionSnapshot {
        public String type = "position-snapshot";
        public List<Entry> entries;

        public PositionSnapshot(List<Entry> entries) {
            this.entries = entries;
        }

        /** One tracked player. Shape matches the mod's PlayerPos record for a clean handoff. */
        public static final class Entry {
            public String id;
            public double x;
            public double y;
            public double z;
            public String dimension;

            public Entry(String id, double x, double y, double z, String dimension) {
                this.id = id;
                this.x = x;
                this.y = y;
                this.z = z;
                this.dimension = dimension;
            }
        }
    }

    /** Tells the client to flash a player red — they signalled they are under attack. */
    public static final class PingBroadcast {
        public String type = "ping-broadcast";
        public String attacker;

        public PingBroadcast(String attacker) {
            this.attacker = attacker;
        }
    }
}
