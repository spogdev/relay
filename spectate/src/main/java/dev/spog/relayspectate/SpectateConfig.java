package dev.spog.relayspectate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.spog.teamlocator.client.TeamLocatorClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The addon's own config file.
 *
 * <p>Separate from Relay's rather than added to it, because the brief was to change as little about
 * Relay as possible: adding a field to its config would change a file that Relay writes and
 * validates, and an older Relay reading a newer file is a compatibility question this avoids
 * entirely.
 *
 * <p>Only the service address is configurable. Everything else that could be a setting -- capture
 * radius, session limits -- is a protocol constant, because both ends have to agree on it and a
 * client-side toggle could not change what the other end sends.
 */
public final class SpectateConfig {
    /**
     * Default service host. Derived from Relay's configured address rather than hard-coded, on the
     * assumption that whoever runs one runs the other; {@link #serviceAddress} overrides it.
     */
    private static final int DEFAULT_PORT = 8081;

    private static SpectateConfig instance;

    /**
     * The spectate service address, as {@code host} or {@code host:port}. Blank means "derive it
     * from Relay's address and the default port".
     */
    public String serviceAddress = "";

    /** Whether to show the indicator when someone is watching. */
    public boolean showWatchedIndicator = true;

    public static synchronized SpectateConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(SpectateClient.MOD_ID + ".json");
    }

    private static SpectateConfig load() {
        Path file = path();
        if (Files.exists(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                SpectateConfig loaded = new Gson().fromJson(json, SpectateConfig.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (IOException | RuntimeException e) {
                SpectateClient.LOGGER.warn("spectate: could not read config, using defaults", e);
            }
        }
        SpectateConfig fresh = new SpectateConfig();
        fresh.save();
        return fresh;
    }

    public void save() {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(path(), gson.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            SpectateClient.LOGGER.warn("spectate: could not write config", e);
        }
    }

    /**
     * The service's WebSocket URL, or null if no address can be determined.
     *
     * <p>Mirrors Relay's scheme choice: {@code wss://} unless the host is plainly local, so a
     * default deployment is encrypted and a developer testing against localhost is not forced to
     * set up TLS.
     */
    public String serviceWebSocketUrl() {
        String address = serviceAddress == null ? "" : serviceAddress.trim();
        if (address.isEmpty()) {
            String relay = TeamLocatorClient.CONFIG.relayUrl;
            if (relay == null || relay.isBlank()) {
                return null;
            }
            // Relay's address may carry its own port; take only the host and apply our default.
            String host = relay.trim();
            int colon = host.indexOf(':');
            if (colon >= 0) {
                host = host.substring(0, colon);
            }
            address = host + ":" + DEFAULT_PORT;
        }
        String scheme = isLocal(address) ? "ws://" : "wss://";
        return scheme + address + "/spectate";
    }

    private static boolean isLocal(String address) {
        String host = address;
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }
}
