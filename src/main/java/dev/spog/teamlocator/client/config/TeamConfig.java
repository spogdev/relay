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

    /** A named trust list (a set of entries). */
    public static class TrustList {
        public List<TrustEntry> trusted = new ArrayList<>();
    }

    // ---- persisted fields (Gson) ----
    public Mode activeMode = Mode.GLOBAL;
    public boolean globalShareEnabled = true;
    /** Receive attack pings (as toasts) from teammates on other servers or while at the menu. */
    public boolean crossServerPings = true;
    /** Per-player client-side ping display cooldown, in seconds (0 = no cooldown). */
    public int pingCooldownSeconds = 15;
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
    /** Master HUD visibility, flipped by the toggle-HUD keybind. */
    public boolean hudEnabled = true;
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
        if (hudScale < 0.5 || hudScale > 2.0) hudScale = 1.0;
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
     * Players whose attack pings should be silenced locally. Pings can only ever arrive from
     * players on the active list (mutual trust is enforced relay-side), so the active list is the
     * complete set of possible senders.
     */
    public Set<UUID> mutedPingSet() {
        Set<UUID> out = new HashSet<>();
        TrustList list = activeList();
        if (list == null) {
            return out;
        }
        for (TrustEntry e : list.trusted) {
            if (e.mutePings) {
                out.add(e.uuid());
            }
        }
        return out;
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
