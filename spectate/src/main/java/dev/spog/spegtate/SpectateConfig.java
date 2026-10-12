package dev.spog.spegtate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
 * <p>Capture radius and session limits are deliberately not settings: both ends have to agree on
 * them, so a client-side toggle could not change what the other end sends. What is configurable is
 * what this client decides locally -- the service address, who may watch, and whether anyone may.
 */
public final class SpectateConfig {
    /**
     * The path the spectate service is served on.
     *
     * <p>The default deployment puts the service behind the same reverse proxy as Relay, on this
     * path, rather than on a port of its own. That is what Relay itself does -- it connects to
     * {@code wss://<host>} with no port and lets the proxy route it -- and it matters because a
     * bare port would have to be open to the internet and serve its own TLS. Sharing the proxy
     * means the service binds to localhost and the existing certificate covers it.
     */
    private static final String SERVICE_PATH = "/spectate";

    /** The service this addon is built against; see {@link #serviceAddress}. */
    private static final String DEFAULT_SERVICE_ADDRESS = "relay.spog.dev";

    private static SpectateConfig instance;

    /**
     * The spectate service address, as {@code host} or {@code host:port}.
     *
     * <p>Defaults to the service this addon is built for, so a fresh install connects without
     * being configured. It stays a config field rather than a constant because a local service is
     * the only practical way to test a change, and {@link #isLocal} exists for exactly that case.
     *
     * <p>This used to be blank and seeded on first run from Relay's address. That made a feature
     * needing one string depend on another mod being installed, and it failed silently on any
     * version Relay does not build for: the seed never ran, the address stayed empty, and the
     * client simply never connected. A default is the same answer without the dependency.
     */
    public String serviceAddress = DEFAULT_SERVICE_ADDRESS;

    /**
     * Whether anyone may spectate this client.
     *
     * <p>The whole permission model. This mod is private and everyone holding it has agreed the
     * others may watch them, so there is no per-player list -- the only question is whether this
     * client is accepting viewers right now, and this is the honest answer to "I do not want to
     * be watched". It is enforced at the service by withholding consent rather than by hiding
     * the UI, so a refusal is not something another client can talk its way past.
     *
     * <p>Defaults to on, which is the agreement the mod is handed out under. Turning it off also
     * cuts off anyone already watching.
     */
    public boolean allowSpectators = true;

    /** Whether to show the indicator when someone is watching. */
    public boolean showWatchedIndicator = true;

    /**
     * Whether the viewer may fly freely instead of riding the target's camera.
     *
     * <p>Free movement shows more than the target can see: the viewer holds whole chunks, so a free
     * camera can be moved behind a wall or underground and read terrain the target has no line of
     * sight to. That is a reasonable thing for a server operator to call an exploit, which is why
     * the locked camera exists and remains available.
     *
     * <p>Default is free movement, at the request of the only people who can start a session: a
     * spectate session requires standing trust from the target, and this build is for a private
     * server whose operator permits it.
     */
    public boolean freeCamera = true;

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
                    if (loaded.serviceAddress == null || loaded.serviceAddress.isBlank()) {
                        // A field default only reaches a config Gson does not overwrite, so every
                        // config written before the address had a default kept its empty string
                        // and silently never connected. Blank is not a meaningful setting -- it
                        // means "no service" -- so treat it as unset and fill it in.
                        loaded.serviceAddress = DEFAULT_SERVICE_ADDRESS;
                        loaded.save();
                    }
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
     * <p>The address carries no port in the usual deployment -- same host, same port, different
     * path -- because the service is expected to sit behind the same reverse proxy as everything
     * else. An earlier version appended a port here, which produced
     * {@code wss://host:8090/spectate}: that bypasses the proxy, so it needed the port open to the
     * internet and serving its own certificate, and against a localhost-bound service it simply
     * could not connect. {@link #serviceAddress} may still carry its own port
     * ({@code host:port}) for a service that really is exposed directly -- a local dev instance,
     * most usefully.
     *
     * <p>Scheme is inferred rather than configured: {@code wss://} unless the host is plainly
     * local, so a real deployment is encrypted by default while a developer testing against
     * localhost is not forced to set up TLS.
     */
    public String serviceWebSocketUrl() {
        String address = serviceAddress == null ? "" : serviceAddress.trim();
        if (address.isEmpty()) {
            return null;
        }
        String scheme = isLocal(address) ? "ws://" : "wss://";
        return scheme + address + SERVICE_PATH;
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
