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
 * per-list share toggle, block list, and HUD position. Serialized to {@code config/teamlocator.json}
 * via Gson. All mutation goes through this instance; call {@link #save()} after changes.
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
    /** WebSocket URL of the user's relay service, e.g. {@code wss://relay.example.com}. */
    public String relayUrl = "";
    /** HUD anchor as a fraction of screen size so it survives resolution / GUI-scale changes. */
    public double hudX = 0.01;
    public double hudY = 0.30;
    public TrustList global = new TrustList();
    public java.util.Map<String, TrustList> servers = new java.util.HashMap<>();
    public List<TrustEntry> blocked = new ArrayList<>();

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
        if (relayUrl == null) relayUrl = "";
        if (global == null) global = new TrustList();
        if (global.trusted == null) global.trusted = new ArrayList<>();
        if (servers == null) servers = new java.util.HashMap<>();
        if (blocked == null) blocked = new ArrayList<>();
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

    public Set<UUID> blockedSet() {
        Set<UUID> out = new HashSet<>();
        for (TrustEntry e : blocked) {
            out.add(e.uuid());
        }
        return out;
    }
}
