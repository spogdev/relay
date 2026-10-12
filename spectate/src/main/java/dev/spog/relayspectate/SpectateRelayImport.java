package dev.spog.relayspectate;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;

/**
 * Seeds this addon's config from Relay's, once, if Relay happens to be installed.
 *
 * <p>This exists for one reason: the addon used to read Relay's trust list and relay address
 * directly, so every existing install's settings live over there. Defaulting the new config to
 * empty would silently revoke everyone's access and unconfigure the service on upgrade, and the
 * player would have no idea why spectating stopped working. So the first run copies what was
 * already in effect, and {@link SpectateConfig#importedFromRelay} makes sure it never runs again --
 * otherwise pruning someone here would be undone at the next launch.
 *
 * <p><strong>Everything is reflective, deliberately.</strong> This is the only class that names
 * Relay at all, and it must not create a load-time dependency: the addon's whole point now is that
 * it runs without Relay installed. A direct reference would make {@code NoClassDefFoundError} the
 * first thing a Relay-less launch hits. Reflection turns an absent Relay into a quiet "nothing to
 * import", which is the correct outcome.
 *
 * <p>The import is one-directional and read-only. Relay's config is never written, so a player who
 * keeps both mods can narrow the spectate list without touching who sees them on the map -- which
 * is the distinction the split was made for in the first place.
 */
public final class SpectateRelayImport {
    private static final String RELAY_MOD_ID = "relay";
    private static final String CLIENT_CLASS = "dev.spog.teamlocator.client.TeamLocatorClient";

    private SpectateRelayImport() {
    }

    /**
     * Copies Relay's address and trust list into {@code config} if this is the first run and Relay
     * is present. Saves only if something was actually imported.
     */
    public static void runOnce(SpectateConfig config) {
        if (config.importedFromRelay) {
            return;
        }
        // Marked before the attempt, not after: a failed or partial import should not be retried
        // every launch, and an import that imports nothing is a legitimate outcome worth recording.
        config.importedFromRelay = true;

        if (!FabricLoader.getInstance().isModLoaded(RELAY_MOD_ID)) {
            config.save();
            return;
        }

        int imported = 0;
        boolean tookAddress = false;
        try {
            Object relayConfig = relayConfig();
            if (relayConfig != null) {
                tookAddress = importAddress(config, relayConfig);
                imported = importTrust(config, relayConfig);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // A Relay whose shape has changed is not an error worth failing the launch over: the
            // player keeps a working addon with an empty list, which they can refill by hand.
            SpectateClient.LOGGER.warn("spectate: could not import settings from Relay", e);
        }

        config.save();
        if (tookAddress || imported > 0) {
            SpectateClient.LOGGER.info(
                    "spectate: imported {} trusted player(s){} from Relay (one time only)",
                    imported, tookAddress ? " and the service address" : "");
        }
    }

    /** Relay's live config object, via its {@code CONFIG} static field. */
    private static Object relayConfig() throws ReflectiveOperationException {
        Class<?> client = Class.forName(CLIENT_CLASS, false,
                SpectateRelayImport.class.getClassLoader());
        Field field = client.getField("CONFIG");
        return field.get(null);
    }

    /** Takes Relay's host as the service address, unless one is already set. */
    private static boolean importAddress(SpectateConfig config, Object relayConfig)
            throws ReflectiveOperationException {
        if (config.serviceAddress != null && !config.serviceAddress.isBlank()) {
            return false;
        }
        Object url = relayConfig.getClass().getField("relayUrl").get(relayConfig);
        if (!(url instanceof String s) || s.isBlank()) {
            return false;
        }
        config.serviceAddress = s.trim();
        return true;
    }

    /**
     * Copies Relay's position-sharing set as the initial spectate list.
     *
     * <p>That set is the right starting point precisely because it is what the addon was already
     * using: importing it reproduces the access that existed before the split, rather than
     * inventing a new grant. From here the two lists diverge, and only this one admits a camera.
     */
    private static int importTrust(SpectateConfig config, Object relayConfig)
            throws ReflectiveOperationException {
        Method sharing = relayConfig.getClass().getMethod("effectiveSharingSet");
        Object result = sharing.invoke(relayConfig);
        if (!(result instanceof Collection<?> ids)) {
            return 0;
        }
        int added = 0;
        for (Object id : ids) {
            if (id instanceof UUID uuid) {
                config.trusted.add(new SpectateTrust.Entry(uuid, nameFor(relayConfig, uuid)));
                added++;
            }
        }
        return added;
    }

    /**
     * Relay's cached display name for a player, so imported rows are not all raw ids.
     *
     * <p>Best-effort: the name is only a label, so any failure here returns null and the entry
     * falls back to a short id rather than losing the import.
     */
    private static String nameFor(Object relayConfig, UUID id) {
        try {
            Object list = relayConfig.getClass().getMethod("activeList").invoke(relayConfig);
            if (list == null) {
                return null;
            }
            Object entries = list.getClass().getField("trusted").get(list);
            if (!(entries instanceof Collection<?> rows)) {
                return null;
            }
            String key = id.toString();
            for (Object row : rows) {
                Object uuid = row.getClass().getField("uuid").get(row);
                if (key.equals(uuid)) {
                    Object name = row.getClass().getField("name").get(row);
                    return name instanceof String s && !s.isBlank() ? s : null;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Label only; an id is a perfectly usable fallback.
        }
        return null;
    }
}
