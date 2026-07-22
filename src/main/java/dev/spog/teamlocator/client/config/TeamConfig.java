package dev.spog.teamlocator.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.spog.teamlocator.TeamLocatorConstants;
import dev.spog.teamlocator.client.gui.SecondsSlider;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Client-side persistent config: the global and per-server trust lists, active-list selection,
 * share toggle, and HUD position. Serialized to {@code config/relay.json} via Gson. All mutation
 * goes through this instance; call {@link #save()} after changes.
 */
public class TeamConfig {
    public enum Mode { SERVER, GLOBAL }

    public enum AlertSound { ALARM, NOTEBLOCKS }

    /**
     * Horizontal alignment of each HUD row (head + text as a unit) within the widest row.
     * {@link #TABLE} is different in kind: instead of shifting whole rows, it lays the coordinate
     * fields out in right-aligned columns from a shared origin, so the X/Y/Z of every row line up
     * and all rows end at the same x — a spreadsheet-style layout.
     */
    public enum HudAlign { LEFT, CENTER, RIGHT, TABLE }

    /**
     * How much of a teammate's armor the HUD shows. {@code LOWEST} keeps only the piece closest to
     * breaking — the one worth knowing about mid-fight — for a HUD that stays narrow.
     */
    public enum ArmorDisplay { OFF, ALL, LOWEST }

    /** A named trust list (a set of entries). */
    public static class TrustList {
        public List<TrustEntry> trusted = new ArrayList<>();
    }

    // ---- persisted fields (Gson) ----
    /**
     * The active-list choice for the current scope. Kept as a live field rather than read from
     * {@link #serverModes} on every access so the menu and singleplayer still have a mode, and so
     * an older config's single choice carries forward. {@link #onScopeChanged()} swaps it to the
     * joined server's remembered choice.
     */
    public Mode activeMode = Mode.GLOBAL;
    /** Remembered active-list choice per server key, so rejoining a server restores its mode. */
    public java.util.Map<String, Mode> serverModes = new java.util.HashMap<>();
    public boolean globalShareEnabled = true;
    /**
     * Share our equipped armor and its durability with trusted players. On by default — it only
     * ever reaches players already trusted with our position (the relay gates armor behind the
     * position-sharing set), so the default matches what a team installing the mod expects to just
     * work. Independent of {@link #globalShareEnabled}.
     */
    public boolean shareArmor = true;
    /** Receive attack pings (as toasts) from teammates on other servers or while at the menu. */
    public boolean crossServerPings = true;
    /**
     * Master mute: when true, no incoming alert is shown at all — no toast, no sound, no HUD flash,
     * from any teammate on any server. A blanket "do not disturb" that overrides everything below
     * ({@link #crossServerPings}, per-player mutes, the cooldown), for when a player wants the
     * sharing HUD without ever being interrupted by alerts. Does not affect whether OUR alerts reach
     * others — only our own inbox.
     */
    public boolean hideAllAlerts = false;
    /**
     * Per-player client-side ping display cooldown, in seconds: a repeat alert from the same player
     * is received but not shown again until it elapses. The slider offers 1s–10m
     * ({@link dev.spog.teamlocator.client.gui.SecondsSlider#COOLDOWN_STEPS}); 0 (show every alert)
     * remains valid if set in the config file directly.
     */
    public int pingCooldownSeconds = 15;
    /** Which sound plays when an attack alert is received. Defaults to the new alarm. */
    public AlertSound alertSound = AlertSound.ALARM;
    /**
     * Which of the five UUID-derived ping colours this player uses, 0-4. The palette itself is not
     * stored: it is derived from the account's UUID on demand, so it cannot drift out of sync with
     * what the relay independently computes when validating a ping.
     */
    public int pingColorIndex = 0;
    /**
     * How long a location ping stays on screen, in seconds. A viewer-side setting: it governs how
     * long <em>this</em> player sees pings, both teammates' and their own, so two people can watch
     * the same ping for different lengths of time. Runs 5s..600s in 5s steps, or
     * {@link SecondsSlider#INFINITE} for pings that stay until replaced or the player disconnects.
     */
    public int pingDisplaySeconds = 30;
    /** Show teammates' location pings at all. */
    public boolean showPings = true;
    /**
     * Draw pings through terrain. On by default because a callout you cannot see is not a callout —
     * the whole point is pointing at something behind a hill. Turning it off makes pings occlude
     * like ordinary geometry, for players who find them visually noisy.
     */
    public boolean pingsThroughWalls = true;

    /**
     * Whether relay chat works at all. Gates both directions: with this off nothing is sent when you
     * type the prefix (the message goes to the Minecraft server as ordinary chat instead) and
     * incoming relay messages are not shown.
     */
    public boolean chatEnabled = true;
    /**
     * Typing this character at the start of a message routes it to trusted players over the relay
     * instead of the Minecraft server. A single character, so it is quick to type and unlikely to
     * collide with normal speech at the start of a line.
     */
    public String chatPrefix = "#";
    /**
     * Minimum gap between pings accepted from any <em>one</em> player, in seconds; 0 is no limit.
     * Per-sender rather than global, so one teammate spamming pings cannot stop everyone else's from
     * showing. Purely a local viewing preference — it filters what this client draws and never
     * affects what other players see.
     */
    public int mapPingCooldownSeconds = 0;

    /** Hostname of the relay service (the {@code wss://} scheme is fixed, not user-editable). */
    public String relayUrl = DEFAULT_RELAY_ADDRESS;
    /**
     * Whether the relay reported this account as an administrator on the last successful connect.
     *
     * <p>Remembered across sessions purely so {@code /relay} can be offered in chat: Minecraft
     * builds its command tree (and evaluates each command's visibility) the moment you join a
     * world, which is <em>before</em> the relay has finished authenticating, so a flag set at auth
     * time would always arrive too late and hide the command for admins too. Persisting last
     * session's answer means the tree is built with the right value from the start.
     *
     * <p>Never a permission: the relay re-checks every command against the Mojang-verified UUID,
     * so editing this to true only makes a command visible that the relay will still refuse.
     */
    public boolean wasRelayAdmin = false;

    public static final String DEFAULT_RELAY_ADDRESS = "relay.spog.dev";

    /** The full WebSocket URL for the configured relay host. */
    public String relayWebSocketUrl() {
        return relayUrl.isBlank() ? "" : "wss://" + relayUrl;
    }

    /** Strip a pasted scheme so the stored value is always a bare host. */
    public static String normalizeRelayAddress(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.regionMatches(true, 0, "wss://", 0, 6)) s = s.substring(6);
        if (s.regionMatches(true, 0, "ws://", 0, 5)) s = s.substring(5);
        return s;
    }
    /** HUD anchor as a fraction of screen size so it survives resolution / GUI-scale changes. */
    public double hudX = 0.01;
    public double hudY = 0.30;
    /** HUD size multiplier (0.5–2.0). */
    public double hudScale = 1.0;
    /**
     * When true the list grows bottom-to-top: the anchor becomes the block's bottom edge and rows
     * stack upward from it (row 1 closest to the anchor), so adding teammates expands the list
     * upward instead of downward.
     */
    public boolean hudGrowUp = false;
    /** How each HUD row is aligned within the widest row. */
    public HudAlign hudAlign = HudAlign.LEFT;
    /** Show each teammate's coordinates (and dimension) on the HUD row. */
    public boolean hudShowCoords = true;
    /**
     * How much of each teammate's armor the HUD row shows. Only ever displays what a teammate
     * chooses to share ({@link #shareArmor}) — this is our own view of it.
     *
     * <p>Named apart from the old boolean {@code hudShowArmor} on purpose: Gson cannot read
     * {@code true} into an enum, and a type clash on one field aborts the whole parse and resets
     * every other setting. {@link #sanitize()} migrates the old value instead.
     */
    public ArmorDisplay hudArmorDisplay = ArmorDisplay.ALL;
    /**
     * The pre-enum boolean, read only to migrate a config written before {@link #hudArmorDisplay}
     * existed. Boxed so a missing field stays null rather than defaulting to false and reading as
     * a deliberate "off". Never written back: {@link #sanitize()} clears it once carried over.
     */
    @Deprecated
    private Boolean hudShowArmor;
    /** HUD text colors as "#RRGGBB": primary = names & punctuation, secondary = numbers & dimension. */
    public String hudPrimaryColor = "#FFFFFF";
    public String hudSecondaryColor = "#AAAAAA";
    /** Master HUD visibility, flipped by the toggle-HUD keybind. */
    public boolean hudEnabled = true;
    /** Show relay teammates on Xaero's Minimap / World Map (when those mods are installed). */
    public boolean xaeroMapIcons = true;
    /**
     * Show Xaero's floating in-world icon for relay teammates (enforced by the tracked-player
     * renderer mixin). Minimap/world-map markers are unaffected.
     */
    public boolean xaeroInWorldIcons = true;
    public TrustList global = new TrustList();
    public java.util.Map<String, TrustList> servers = new java.util.HashMap<>();

    // ---- persistence ----
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH =
            FabricLoader.getInstance().getConfigDir().resolve(TeamLocatorConstants.MOD_ID + ".json");

    public static TeamConfig load() {
        try {
            if (Files.exists(PATH)) {
                String json = Files.readString(PATH, StandardCharsets.UTF_8);
                TeamConfig cfg = GSON.fromJson(json, TeamConfig.class);
                if (cfg != null) {
                    cfg.sanitize();
                    return cfg;
                }
            }
        } catch (Exception e) {
            TeamLocatorConstants.LOGGER.error("Failed to load TeamLocator config, using defaults", e);
        }
        TeamConfig cfg = new TeamConfig();
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            TeamLocatorConstants.LOGGER.error("Failed to save TeamLocator config", e);
        }
    }

    /** Repair nulls that a partial/older JSON file may leave after deserialization. */
    private void sanitize() {
        if (activeMode == null) activeMode = Mode.GLOBAL;
        if (serverModes == null) serverModes = new java.util.HashMap<>();
        serverModes.values().removeIf(java.util.Objects::isNull);
        if (alertSound == null) alertSound = AlertSound.ALARM;
        if (hudScale < 0.5 || hudScale > 2.0) hudScale = 1.0;
        if (hudAlign == null) hudAlign = HudAlign.LEFT;
        // Carry the pre-enum boolean over once, then drop it so it never overrides the enum again.
        if (hudShowArmor != null) {
            hudArmorDisplay = hudShowArmor ? ArmorDisplay.ALL : ArmorDisplay.OFF;
            hudShowArmor = null;
        }
        if (hudArmorDisplay == null) hudArmorDisplay = ArmorDisplay.ALL;
        if (!isValidHex(hudPrimaryColor)) hudPrimaryColor = "#FFFFFF";
        if (!isValidHex(hudSecondaryColor)) hudSecondaryColor = "#AAAAAA";
        // Upper bound matches the cooldown slider's longest step (10 minutes). It was 60 when the
        // slider only went that high; leaving it there would silently reset any longer cooldown
        // back to 15s on the next load.
        if (pingCooldownSeconds < 0 || pingCooldownSeconds > 600) pingCooldownSeconds = 15;
        if (pingColorIndex < 0 || pingColorIndex >= 5) pingColorIndex = 0;
        // -1 (Infinite) is a valid setting, so only reject values outside the range that are not it.
        if (pingDisplaySeconds != SecondsSlider.INFINITE
                && (pingDisplaySeconds < 1 || pingDisplaySeconds > 600)) {
            pingDisplaySeconds = 30;
        }
        // 0 means "no cooldown" and is the default, so the floor here is 0 rather than 1.
        if (mapPingCooldownSeconds < 0 || mapPingCooldownSeconds > 600) mapPingCooldownSeconds = 0;
        // A blank or multi-character prefix would either swallow every message or never match, so
        // fall back rather than leave the user unable to chat normally. Whitespace is rejected for
        // the same reason: a space prefix would capture ordinary sentences.
        if (chatPrefix == null || chatPrefix.length() != 1
                || Character.isWhitespace(chatPrefix.charAt(0))) {
            chatPrefix = "#";
        }
        if (relayUrl == null || relayUrl.isBlank()) relayUrl = DEFAULT_RELAY_ADDRESS;
        relayUrl = normalizeRelayAddress(relayUrl);
        if (global == null) global = new TrustList();
        if (global.trusted == null) global.trusted = new ArrayList<>();
        if (servers == null) servers = new java.util.HashMap<>();
        servers.values().forEach(l -> {
            if (l.trusted == null) l.trusted = new ArrayList<>();
        });
    }

    // ---- server-address keying ----

    /**
     * Normalized key for the currently connected server, or {@code null} if not connected.
     * Singleplayer/LAN maps to {@code "singleplayer"}.
     *
     * <p>Keyed on the live connection's <em>resolved</em> peer — the IP and port the client is
     * actually talking to — as {@code ip:port}, rather than on the address the user typed. Two
     * players on one server must produce the same key or the relay files them under different
     * scopes and they never see each other, and the typed address does not guarantee that: one can
     * use the hostname while the other uses the IP, and both are correct. The socket is the same
     * for everyone on the server whatever route they took to it, so it is the only honest identity
     * available client-side.
     *
     * <p>The port stays in the key: shared hosts (Folium and friends) put unrelated servers on one
     * box, distinguished only by port, and dropping it would merge strangers into one scope.
     * {@code :25565} is no longer special-cased — the resolved port is always present and explicit,
     * so there is no {@code host} vs {@code host:25565} ambiguity left to paper over.
     */
    public static String currentServerKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isLocalServer()) {
            return "singleplayer";
        }
        String resolved = resolvedServerKey(mc);
        if (resolved != null) {
            return resolved;
        }
        // No live connection yet (called between joining and the channel coming up): fall back to
        // the typed address so a key exists at all, normalized the old way.
        ServerData data = mc.getCurrentServer();
        if (data == null || data.ip == null || data.ip.isBlank()) {
            return null;
        }
        String ip = data.ip.trim().toLowerCase(Locale.ROOT);
        if (ip.endsWith(":25565")) {
            ip = ip.substring(0, ip.length() - ":25565".length());
        }
        return ip;
    }

    /**
     * {@code ip:port} of the live connection's peer, or null if there is no resolved TCP peer to
     * read (no connection, or a non-IP transport such as a LAN/test channel).
     */
    private static String resolvedServerKey(Minecraft mc) {
        if (mc.getConnection() == null) {
            return null;
        }
        java.net.SocketAddress remote = mc.getConnection().getConnection().getRemoteAddress();
        if (!(remote instanceof java.net.InetSocketAddress inet) || inet.getAddress() == null) {
            return null;
        }
        // getHostAddress(), never getHostString()/toString(): those hand back the hostname the
        // client happened to dial, which is the very thing that differs between two players.
        return inet.getAddress().getHostAddress().toLowerCase(Locale.ROOT) + ":" + inet.getPort();
    }

    /**
     * Switch {@link #activeMode} to the joined server's remembered choice. Called on join (and on
     * disconnect, where the scope has no memory and GLOBAL is the only usable mode — the server
     * list is unreachable at the menu). A server we have never seen keeps the current mode, so the
     * first join after picking a mode inherits it rather than snapping back to GLOBAL.
     *
     * @return true if the mode actually changed, meaning callers should re-push trust to the relay
     */
    public boolean onScopeChanged() {
        String key = currentServerKey();
        Mode previous = activeMode;
        if (key == null) {
            activeMode = Mode.GLOBAL; // at the menu: no server list exists to be active
        } else {
            activeMode = serverModes.getOrDefault(key, activeMode);
            // Remember the inherited choice so this server is pinned from now on.
            serverModes.put(key, activeMode);
        }
        save();
        return activeMode != previous;
    }

    /** Record the user's active-list choice for the current server, if we are on one. */
    public void rememberActiveMode() {
        String key = currentServerKey();
        if (key != null) {
            serverModes.put(key, activeMode);
        }
    }

    /** The trust list backing the current active mode, creating the per-server list on demand. */
    public TrustList activeList() {
        if (activeMode == Mode.GLOBAL) {
            return global;
        }
        String key = currentServerKey();
        if (key == null) {
            return null; // not connected: the server list is unavailable
        }
        return servers.computeIfAbsent(key, k -> new TrustList());
    }

    // ---- HUD color helpers ----

    /** True for a "#RRGGBB" hex string. */
    public static boolean isValidHex(String s) {
        return s != null && s.matches("#[0-9a-fA-F]{6}");
    }

    public int hudPrimaryArgb() {
        return parseHex(hudPrimaryColor, 0xFFFFFFFF);
    }

    public int hudSecondaryArgb() {
        return parseHex(hudSecondaryColor, 0xFFAAAAAA);
    }

    private static int parseHex(String s, int fallback) {
        return isValidHex(s) ? 0xFF000000 | Integer.parseInt(s.substring(1), 16) : fallback;
    }

    // ---- derived sets used by networking ----

    /**
     * The effective outbound trust set uploaded to the server: the UUIDs of everyone in the active
     * list who is not hidden — or an empty set when sharing is disabled or no active list exists.
     */
    public Set<UUID> effectiveSharingSet() {
        Set<UUID> out = new HashSet<>();
        TrustList list = activeList();
        if (!globalShareEnabled || list == null) {
            return out;
        }
        for (TrustEntry e : list.trusted) {
            if (!e.hidden) {
                out.add(e.uuid());
            }
        }
        return out;
    }

    /**
     * The alert-trust set uploaded to the server: everyone on the global list plus the active
     * list, hidden entries included ({@code hidden} withholds coordinates, not alerts), and
     * independent of the share toggle. This is what keeps mutual-trust pings working at the menu,
     * in singleplayer, or on another server — anywhere the sharing set is empty or server-scoped.
     */
    public Set<UUID> alertTrustSet() {
        Set<UUID> out = new HashSet<>();
        for (TrustEntry e : global.trusted) {
            out.add(e.uuid());
        }
        TrustList list = activeList();
        if (list != null && list != global) {
            for (TrustEntry e : list.trusted) {
                out.add(e.uuid());
            }
        }
        return out;
    }

    /**
     * Players whose attack pings should be silenced locally. Pings can arrive from anyone in the
     * {@link #alertTrustSet() alert-trust set} (mutual trust is enforced relay-side), so a mute on
     * either the global or the active list wins — including at the menu, where no server list
     * exists.
     */
    public Set<UUID> mutedPingSet() {
        Set<UUID> out = new HashSet<>();
        collectMuted(global, out);
        TrustList list = activeList();
        if (list != null && list != global) {
            collectMuted(list, out);
        }
        return out;
    }

    private static void collectMuted(TrustList list, Set<UUID> out) {
        for (TrustEntry e : list.trusted) {
            if (e.mutePings) {
                out.add(e.uuid());
            }
        }
    }

    /**
     * Best-effort display name for a player, from the cached name on any trust-list entry (global
     * first, then every per-server list). Used for cross-server ping toasts, where the attacker is
     * not in our tab list. Null if we have no entry for them.
     */
    public String nameFor(UUID id) {
        String fromGlobal = nameIn(global, id);
        if (fromGlobal != null) {
            return fromGlobal;
        }
        for (TrustList list : servers.values()) {
            String name = nameIn(list, id);
            if (name != null) {
                return name;
            }
        }
        return null;
    }

    private static String nameIn(TrustList list, UUID id) {
        for (TrustEntry e : list.trusted) {
            try {
                if (e.uuid().equals(id) && e.name != null && !e.name.isBlank()) {
                    return e.name;
                }
            } catch (RuntimeException ignored) {
                // corrupt uuid string in config; skip
            }
        }
        return null;
    }
}
