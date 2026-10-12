package dev.spog.relayspectate;

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

    private static SpectateConfig instance;

    /**
     * The spectate service address, as {@code host} or {@code host:port}.
     *
     * <p>Required. This used to fall back to Relay's configured host, which is what made Relay a
     * hard dependency for a feature that otherwise only needed an address; the addon now asks for
     * its own. On first run it is seeded from Relay's address if Relay happens to be installed
     * (see {@link SpectateRelayImport}), so an existing setup keeps working without being
     * reconfigured, but nothing afterwards consults Relay.
     */
    public String serviceAddress = "";

    /**
     * Whether anyone may spectate this client at all.
     *
     * <p>A master switch over {@link #trusted}, and the honest answer to "I do not want to be
     * watched right now": it empties the set uploaded to the service, so the refusal happens at
     * the service rather than relying on the other client to behave. Turning it off does not
     * forget the list, so turning it back on restores exactly who was trusted before.
     *
     * <p>Defaults to on, matching the previous behaviour -- an upgrade should not silently revoke
     * access that already worked.
     */
    public boolean allowSpectators = true;

    /**
     * Who may watch this client, and who the menu offers to watch.
     *
     * <p>The addon's own list rather than Relay's position-sharing set, because those answer
     * different questions -- see {@link SpectateTrust}.
     */
    public java.util.List<SpectateTrust.Entry> trusted = new java.util.ArrayList<>();

    /**
     * Whether the one-time import from Relay's trust list has already run.
     *
     * <p>Recorded so the import happens once and not every launch. Without it, pruning someone
     * from the spectate list would be undone at the next start by re-importing them from Relay,
     * and there would be no way to keep a spectate list narrower than the map-sharing one.
     */
    public boolean importedFromRelay = false;

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
            // Assigned before the import runs, because the import calls save(), which would
            // otherwise re-enter get() on a still-null instance and recurse.
            instance = load();
            SpectateRelayImport.runOnce(instance);
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
                    if (loaded.trusted == null) {
                        // Absent from a config written before the list existed, and Gson leaves
                        // it null rather than keeping the field initialiser.
                        loaded.trusted = new java.util.ArrayList<>();
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
