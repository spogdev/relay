package dev.spog.teamlocator.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.spog.teamlocator.TeamLocatorConstants;
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

    /** Horizontal alignment of each HUD row (head + text as a unit) within the widest row. */
    public enum HudAlign { LEFT, CENTER, RIGHT }

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
     * Share our equipped armor and its durability with trusted players. Off by default: gear state
     * is a bigger disclosure than coordinates, so it is opted into rather than out of. Independent
     * of {@link #globalShareEnabled}, but the relay gates armor behind the position-sharing set, so
     * armor never reaches anyone who cannot already see us.
     */
    public boolean shareArmor = false;
    /** Receive attack pings (as toasts) from teammates on other servers or while at the menu. */
    public boolean crossServerPings = true;
    /** Per-player client-side ping display cooldown, in seconds (0 = no cooldown). */
    public int pingCooldownSeconds = 15;
    /** Which sound plays when an attack alert is received. Defaults to the new alarm. */
    public AlertSound alertSound = AlertSound.ALARM;
    /** Hostname of the relay service (the {@code wss://} scheme is fixed, not user-editable). */
    public String relayUrl = DEFAULT_RELAY_ADDRESS;

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
        if (pingCooldownSeconds < 0 || pingCooldownSeconds > 60) pingCooldownSeconds = 15;
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
     * Lowercased, trimmed, with a redundant default port stripped so {@code host} and
     * {@code host:25565} map to the same list. Singleplayer/LAN maps to {@code "singleplayer"}.
     */
    public static String currentServerKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isLocalServer()) {
            return "singleplayer";
        }
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
