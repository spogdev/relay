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

    /**
     * The protocol version this build speaks. History, all additive on the wire:
     * <ul>
     *   <li>v1 — positions, pings, and a single trust set ({@code sharingWith} doubled as the
     *       alert set).</li>
     *   <li>v2 — split alerts from sharing ({@code alertsWith}) and added armor
     *       ({@code armor-update} and the {@code armor} field on snapshot entries).</li>
     *   <li>v3 — availability probes ({@code availability-query} / {@code -probe} / {@code -response}
     *       / {@code -result}), backing the client's {@code /available} command. The relay only
     *       probes v3+ sessions, so older clients never see an unknown frame.</li>
     * </ul>
     *
     * <p>Every field added since v1 is <em>optional</em> on the wire, and every message a client
     * receives that it does not understand is ignored (unknown JSON fields are dropped by the
     * parser; unknown message types hit a no-op default). That is what makes cross-version support
     * possible: an old client omits the newer fields and the relay fills in the pre-split default,
     * while a new client's extra fields are harmless to an old relay. Keep it that way — a
     * <em>non</em>-additive change (renaming or removing a field, or changing its meaning) would
     * break this and would need a real negotiation, not just a version bump.
     */
    public static final int PROTOCOL_VERSION = 3;

    /** The lowest protocol version whose clients understand availability probes. */
    public static final int AVAILABILITY_MIN_VERSION = 3;

    /**
     * The oldest client protocol the relay still accepts. The relay serves anything in
     * {@code [MIN_SUPPORTED_VERSION, PROTOCOL_VERSION]}, and treats a <em>higher</em>-versioned
     * client as if it spoke {@code PROTOCOL_VERSION} (its unknown newer fields are ignored, so it
     * degrades to what this relay understands rather than being turned away). Raise this floor only
     * if a version ever becomes genuinely unroutable — see the additive-design note above; so far
     * none has.
     */
    public static final int MIN_SUPPORTED_VERSION = 1;

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
        /**
         * The players this client exchanges attack pings with. Unlike {@code sharingWith} it is
         * not emptied by the share toggle and includes the global list, so alerts keep working
         * from the menu or another server. Absent from an old client, whose {@code sharingWith}
         * then doubles as the alert set (the pre-split behavior).
         */
        public List<String> alertsWith;
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
        /**
         * Health in half-hearts. Carried here rather than in its own message because it changes on
         * the same cadence as position and is gated by the same sharing trust; a separate frame
         * would double the chattiest path in the protocol for nothing. Absent from older clients.
         */
        public float health;
        /**
         * The sender's chosen ping colour as {@code #rrggbb}, so teammates can tint that player's
         * HUD row to match their pings. Null from older clients.
         *
         * <p>Rides along here despite changing far less often than position. Announcing it once at
         * auth would be cheaper, but then a player who changed colour mid-session would keep the old
         * tint on everyone's HUD until they reconnected; sent per update it simply corrects itself.
         *
         * <p>Advisory only — the relay re-resolves it against the sender's own palette before
         * passing it on, so a client cannot paint itself a colour it has no claim to.
         */
        public String pingColor;
    }

    /** "I'm being attacked." The relay fans it out to mutual-trust peers who haven't blocked us. */
    public static final class Ping {
        public String type = "ping";
    }

    /**
     * The client's own equipped armor and its durability, for teammates' HUDs. Sent separately from
     * {@link PositionUpdate} — and far less often — because armor changes rarely while position
     * changes constantly; the client only sends this when a piece actually changes.
     *
     * <p>Self-reported like everything else here (see the README's stated limits), and routed
     * through the same sharing gate as positions, with its own client-side opt-in on top.
     */
    public static final class ArmorUpdate {
        public String type = "armor-update";
        /** Equipped pieces, head-to-feet order; absent/empty slots are simply omitted. */
        public List<ArmorPiece> armor;
    }

    /**
     * One equipped armor piece. {@code item} is the registry id (e.g. "minecraft:diamond_chestplate")
     * so the viewer's client can resolve the real item and render its icon; {@code damage} and
     * {@code maxDamage} mirror {@code ItemStack}'s own values, so the HUD can show durability the
     * same way the inventory does. {@code maxDamage <= 0} means the piece has no durability bar
     * (unbreakable, or a non-damageable item like a carved pumpkin).
     */
    public static final class ArmorPiece {
        /** Equipment slot: "head", "chest", "legs", or "feet". */
        public String slot;
        public String item;
        public int damage;
        public int maxDamage;

        public ArmorPiece() {
        }

        public ArmorPiece(String slot, String item, int damage, int maxDamage) {
            this.slot = slot;
            this.item = item;
            this.damage = damage;
            this.maxDamage = maxDamage;
        }
    }

    /**
     * "Which of my mutually-trusting peers would actually see my alert right now?" — backs the
     * client's {@code /available} command. The relay cannot answer alone: whether an alert is
     * <em>displayed</em> is decided receiver-side (mutes, the cross-server toggle, and the
     * known-server gate), so the relay fans out an {@link AvailabilityProbe} to each candidate and
     * aggregates their answers into an {@link AvailabilityResult}.
     */
    public static final class AvailabilityQuery {
        public String type = "availability-query";
    }

    /**
     * A client's answer to an {@link AvailabilityProbe}: whether an alert from that attacker would
     * be shown on this client's screen right now. Evaluated silently — no toast, no sound.
     */
    public static final class AvailabilityResponse {
        public String type = "availability-response";
        public String probeId;
        public boolean available;
    }

    // ---- relay -> client ----

    /**
     * Asks a client to evaluate — without displaying anything — whether an alert from
     * {@code attacker} (whose scope is {@code mcServer}) would be shown on its screen, exactly as
     * {@code PingHandler} would decide for a real ping. Only ever sent to sessions that negotiated
     * {@link #AVAILABILITY_MIN_VERSION} or newer; older clients would silently drop it and stall
     * the aggregate, so the relay excludes them up front.
     */
    public static final class AvailabilityProbe {
        public String type = "availability-probe";
        public String probeId;
        public String attacker;
        public String mcServer;

        public AvailabilityProbe(String probeId, String attacker, String mcServer) {
            this.probeId = probeId;
            this.attacker = attacker;
            this.mcServer = mcServer;
        }
    }

    /**
     * The aggregate answer to an {@link AvailabilityQuery}: the UUIDs of every probed peer that
     * said it would display the alert. Sent when all probes answered or the probe window timed
     * out — a peer that never answers (lagging, or mid-disconnect) is simply not listed, keeping
     * the promise that every listed player will actually see the alert.
     */
    public static final class AvailabilityResult {
        public String type = "availability-result";
        public List<String> receivers;

        public AvailabilityResult(List<String> receivers) {
            this.receivers = receivers;
        }
    }

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
        /**
         * Whether this player administers the relay. Purely a UI hint, so the client can hide the
         * {@code /relay} command from players who cannot use it — authorization is always rechecked
         * relay-side against the verified UUID, so a patched client that forces this true still has
         * every command refused. Absent from an older relay, which the client reads as false.
         */
        public boolean admin;

        public AuthOk(String uuid, boolean admin) {
            this.uuid = uuid;
            this.admin = admin;
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

    /**
     * The {@link AuthFail#reason} sent to a blacklisted player. Deliberately carried on the existing
     * auth-fail rather than a new message type: an older client already knows how to handle
     * auth-fail and will show its generic disconnect, while a current client recognises this exact
     * code and says the player is banned. A new type would be silently ignored by old clients,
     * leaving them retrying forever with no explanation.
     *
     * <p>Match on this constant, never on the human-readable text after it.
     */
    public static final String REASON_BANNED = "banned";

    /**
     * "I am marking this spot." A location ping, placed where the sender was looking. Unlike
     * {@link Ping} (the attack alert) this is tied to a position, so it is routed within the
     * sender's scope only — coordinates from another Minecraft server would point somewhere
     * meaningless in the viewer's world.
     *
     * <p>{@code color} is the sender's choice from their own UUID-derived palette. The relay does
     * not take it on trust: see {@link PingColors} and the validation in {@code RelayRouter}.
     */
    public static final class MapPing {
        public String type = "map-ping";
        public double x;
        public double y;
        public double z;
        public String dimension;
        public String color;
    }

    /**
     * A teammate's location ping, fanned out to everyone who mutually alert-trusts them in the same
     * scope. {@code color} is the value the relay settled on — the sender's validated choice, or an
     * administrator's override — never the raw value the client sent.
     */
    public static final class MapPingBroadcast {
        public String type = "map-ping-broadcast";
        public String player;
        public double x;
        public double y;
        public double z;
        public String dimension;
        public String color;

        public MapPingBroadcast(String player, double x, double y, double z, String dimension,
                                String color) {
            this.player = player;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
            this.color = color;
        }
    }

    /**
     * Withdraw the sender's own map ping, so it disappears for teammates as well as for them.
     *
     * <p>Carries no coordinates or identity: the relay already knows whose ping it is from the
     * verified session, and a player only ever has one, so there is nothing to name. That also
     * means a client cannot ask the relay to clear anyone else's.
     */
    public static final class RemoveMapPing {
        public String type = "remove-map-ping";
    }

    /** Tells a viewer to drop the named player's ping. */
    public static final class MapPingRemoved {
        public String type = "map-ping-removed";
        public String player;

        public MapPingRemoved(String player) {
            this.player = player;
        }
    }

    // ---- chat (client -> relay -> client) ----

    /**
     * A chat message typed with the client's relay-chat prefix, to be fanned out to the sender's
     * mutually trusted peers instead of the Minecraft server.
     *
     * <p>Routed within the sender's scope only. The relay never sees the prefix character — that is
     * a client-side trigger, and different users may configure different ones.
     */
    public static final class ChatMessage {
        public String type = "chat";
        public String text;
    }

    /**
     * A relay chat message delivered to a recipient.
     *
     * <p>{@code recipients} lists everyone the relay actually delivered this copy to, so the client
     * can show who could read it. It is the relay's own resolution of the trust rules, not anything
     * the sender asserted — a client cannot inflate or fake the audience of its own message.
     *
     * <p>Note this deliberately discloses the audience to every recipient, not just the sender: two
     * people who both trust the sender but not each other will see one another's names here. That is
     * the group-chat model, and everyone listed can already read each other's replies.
     */
    public static final class ChatBroadcast {
        public String type = "chat-broadcast";
        public String player;
        public String text;
        public java.util.List<String> recipients;

        public ChatBroadcast(String player, String text, java.util.List<String> recipients) {
            this.player = player;
            this.text = text;
            this.recipients = recipients;
        }
    }

    // ---- waypoints (client -> relay -> client) ----

    /**
     * A map waypoint the sender is offering to the players they trust.
     *
     * <p>Unlike chat and pings this is <b>one-way</b>: it goes to everyone the sender shares with,
     * whether or not those players trust the sender back. Sharing a location is a gift rather than a
     * conversation, so requiring reciprocity would make it useless in the common case of showing a
     * base to someone who has not added you yet. Muting still suppresses it — a mute is an explicit
     * "I want nothing from this player".
     */
    public static final class ShareWaypoint {
        public String type = "share-waypoint";
        public String name;
        public int x;
        public int y;
        public int z;
        public String dimension;
    }

    /** A waypoint another player shared. The client presents it as an offer, never auto-adding it. */
    public static final class WaypointBroadcast {
        public String type = "waypoint-broadcast";
        public String player;
        public String name;
        public int x;
        public int y;
        public int z;
        public String dimension;

        public WaypointBroadcast(String player, String name, int x, int y, int z,
                                 String dimension) {
            this.player = player;
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
        }
    }

    // ---- admin (client -> relay -> client) ----

    /**
     * An admin invoking {@code /relay <command> [args]}. Authorization is by the relay-verified
     * UUID of the sending session against the relay's admin list — never anything in this frame, so
     * a patched client cannot promote itself by asserting admin here.
     */
    public static final class AdminCommand {
        public String type = "admin-command";
        public String command;
        public List<String> args;

        public AdminCommand(String command, List<String> args) {
            this.command = command;
            this.args = args;
        }
    }

    /**
     * The relay's reply to an {@link AdminCommand}: lines to print in the admin's chat. A list
     * rather than one string so multi-line output (a status report) stays one frame and one
     * coherent block in chat.
     */
    public static final class AdminResult {
        public String type = "admin-result";
        public List<String> lines;
        /** True when the command failed, so the client can colour the output as an error. */
        public boolean error;

        public AdminResult(List<String> lines, boolean error) {
            this.lines = lines;
            this.error = error;
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
            /**
             * The player's last reported armor, or null if they share none (opted out, or an
             * unarmored player). Null rather than an empty list so "shares no armor" and "shares
             * armor, currently wearing none" stay distinguishable on the wire.
             */
            public List<ArmorPiece> armor;
            /**
             * Health in half-hearts, or -1 when unknown — an older client that never reports it, or
             * one that has not sent a position yet. Negative rather than 0 because 0 is a real
             * value meaning dead, and a HUD that renders "unknown" as "dead" would be alarming.
             */
            public float health;
            /**
             * The player's ping colour as {@code #rrggbb}, already resolved by the relay, or null if
             * they have not reported one. Lets a joining client tint HUD rows straight away rather
             * than waiting for each player's next position update.
             */
            public String pingColor;

            public Entry(String id, double x, double y, double z, String dimension,
                         List<ArmorPiece> armor, float health, String pingColor) {
                this.id = id;
                this.x = x;
                this.y = y;
                this.z = z;
                this.dimension = dimension;
                this.armor = armor;
                this.health = health;
                this.pingColor = pingColor;
            }
        }
    }

    /**
     * Tells the client a mutually-trusted player signalled they are under attack. Unlike positions,
     * pings cross MC-server scopes; {@code mcServer} is the attacker's scope so the client can tell
     * a same-server ping (flash them red on the HUD) from a cross-server one (show a toast naming
     * that server).
     */
    public static final class PingBroadcast {
        public String type = "ping-broadcast";
        public String attacker;
        public String mcServer;

        public PingBroadcast(String attacker, String mcServer) {
            this.attacker = attacker;
            this.mcServer = mcServer;
        }
    }

    /**
     * Relay's reply to {@link Ping}: the UUIDs the ping was actually delivered to (possibly
     * nobody), so the pinger's client can tell them whether help was alerted.
     */
    public static final class PingAck {
        public String type = "ping-ack";
        public List<String> receivers;

        public PingAck(List<String> receivers) {
            this.receivers = receivers;
        }
    }
}
