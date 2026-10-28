package dev.spog.teamlocator.relay;

import dev.spog.stats.BackendLink;
import dev.spog.stats.PlayerRef;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The relay's view of the SpogStats dashboard, which owns all moderation: the relay's blacklist or
 * whitelist, the global blacklist, and administrators' ping-colour overrides. The relay reports
 * its verified players there once a minute and gets the current rules back.
 */
interface Dashboard {
    /** Whether this Mojang-verified player may use the relay. */
    boolean allows(UUID player);

    /** An administrator's forced ping colour for this player ({@code #rrggbb}), or null. */
    String pingColor(UUID player);

    /** A connected player as reported to the dashboard. */
    record Connected(UUID uuid, String name, String modVersion, int protocol, String server) {
    }

    /** No dashboard configured: everyone is allowed and nobody has an override. */
    Dashboard OPEN = new Dashboard() {
        @Override
        public boolean allows(UUID player) {
            return true;
        }

        @Override
        public String pingColor(UUID player) {
            return null;
        }
    };

    /**
     * Link to a dashboard. The rules are cached in {@code cacheFile}, so a relay restarted while
     * the dashboard is unreachable keeps enforcing the last rules it had.
     */
    static Dashboard link(URI endpoint, String secret, Path cacheFile,
                          Supplier<List<Connected>> players, Runnable onRulesChanged) {
        BackendLink link = BackendLink.builder("relay", secret)
                .endpoint(endpoint)
                .cacheFile(cacheFile)
                .players(() -> players.get().stream()
                        .map(c -> new PlayerRef(c.uuid(), c.name(), attrs(c)))
                        .toList())
                .onPolicy(policy -> onRulesChanged.run())
                .start();
        return new Dashboard() {
            @Override
            public boolean allows(UUID player) {
                return link.allows(player);
            }

            @Override
            public String pingColor(UUID player) {
                return link.pingColor(player);
            }
        };
    }

    /** In the order the dashboard shows them; mods older than the modVersion hello field leave it out. */
    private static Map<String, String> attrs(Connected c) {
        Map<String, String> out = new LinkedHashMap<>();
        if (c.modVersion() != null) {
            out.put("modVersion", c.modVersion());
        }
        out.put("protocol", "v" + c.protocol());
        out.put("server", c.server() == null ? "unknown" : c.server());
        return out;
    }
}
