package dev.spog.teamlocator.relay;

import dev.spog.teamlocator.relay.admin.AdminStore;
import dev.spog.teamlocator.relay.admin.BlacklistStore;
import dev.spog.teamlocator.relay.admin.MojangNameResolver;
import dev.spog.teamlocator.relay.admin.NameResolver;
import dev.spog.teamlocator.relay.admin.PingColorStore;
import dev.spog.teamlocator.relay.admin.StatsStore;
import dev.spog.teamlocator.relay.protocol.PingColors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The relay's moderation layer: who may administer it, who is barred from it, and the execution of
 * the {@code /relay} subcommands.
 *
 * <p><b>Authorization is by verified UUID only.</b> Every command is checked against the session's
 * Mojang-verified identity ({@link Session#uuid()}), never against anything the client sent, so a
 * patched client cannot claim to be an admin. Admins are fixed at startup; the blacklist is mutable
 * and persisted on every change.
 *
 * <p>An admin can never be blacklisted. That rule is enforced here rather than in the store so the
 * refusal can be reported to the caller, and it holds regardless of which admin issues it — there is
 * deliberately no way for admins to remove each other's access over the network; that requires
 * editing {@code admins.json} and restarting, which already needs server access.
 */
public final class AdminService {
    private static final Logger LOG = LoggerFactory.getLogger(AdminService.class);

    /** What a command produced: lines to show the caller, and whether it failed. */
    public record Result(List<String> lines, boolean error) {
        static Result ok(String... lines) {
            return new Result(List.of(lines), false);
        }

        static Result fail(String line) {
            return new Result(List.of(line), true);
        }
    }

    /** Looks up a connected player by name; supplied by the server so we can see live sessions. */
    public interface ConnectedLookup {
        /** The verified UUID of a connected player with this name, or empty. */
        Optional<UUID> byName(String name);

        /** The protocol version a connected player negotiated, or empty if not connected. */
        Optional<Integer> versionOf(UUID player);

        /** The scope (MC server) a connected player is in, or empty if not connected. */
        Optional<String> scopeOf(UUID player);

        /** How many players are authenticated right now. */
        int connectedCount();

        /** How many distinct Minecraft servers those players are spread across. */
        int scopeCount();
    }

    private final AdminStore adminStore;
    private final BlacklistStore blacklist;
    private final StatsStore stats;
    private final PingColorStore pingColors;
    private final NameResolver names;
    private final boolean enabled;

    private AdminService(AdminStore adminStore, BlacklistStore blacklist, StatsStore stats,
                         PingColorStore pingColors, NameResolver names, boolean enabled) {
        this.adminStore = adminStore;
        this.blacklist = blacklist;
        this.stats = stats;
        this.pingColors = pingColors;
        this.names = names;
        this.enabled = enabled;
    }

    /** Load the files from {@code dataDir} and talk to Mojang for offline name lookups. */
    public static AdminService load(Path dataDir) {
        AdminStore adminStore = AdminStore.load(dataDir.resolve("admins.json"));
        BlacklistStore blacklist = BlacklistStore.load(dataDir.resolve("blacklist.json"));
        StatsStore stats = StatsStore.load(dataDir.resolve("stats.json"));
        PingColorStore pingColors = PingColorStore.load(dataDir.resolve("pingcolors.json"));
        return new AdminService(adminStore, blacklist, stats, pingColors,
                new MojangNameResolver(), true);
    }

    /** Test seam: explicit stores and a stubbed name resolver, no disk or network. */
    static AdminService of(AdminStore adminStore, BlacklistStore blacklist, StatsStore stats,
                           PingColorStore pingColors, NameResolver names) {
        return new AdminService(adminStore, blacklist, stats, pingColors, names, true);
    }

    /**
     * A no-op service: nobody is an admin and nobody is banned. Used by the plain constructor and
     * the routing tests, so relays that never configure moderation behave exactly as before.
     */
    static AdminService disabled() {
        return new AdminService(null, null, null, null, null, false);
    }

    /** An administrator's ping-colour override for this player, or null if they have none. */
    public String pingColorOverride(UUID player) {
        return enabled ? pingColors.get(player) : null;
    }

    /**
     * Note that a player authenticated, for the all-time unique count. Safe to call on every login;
     * only a genuinely new player causes a write.
     */
    public void recordLogin(UUID player) {
        if (enabled) {
            stats.record(player);
        }
    }

    public boolean isAdmin(UUID player) {
        return enabled && adminStore.isAdmin(player);
    }

    public boolean isBanned(UUID player) {
        return enabled && blacklist.isBanned(player);
    }

    /**
     * Run a subcommand on behalf of {@code caller}. Returns the lines to send back. The caller's
     * admin status is re-checked here rather than trusted from the call site.
     */
    public Result execute(UUID caller, String callerName, String command, List<String> args,
                          ConnectedLookup connected) {
        if (!enabled || !isAdmin(caller)) {
            // Same message either way: an unprivileged caller learns nothing about whether the
            // relay even has moderation configured.
            return Result.fail("You do not have permission to use this command.");
        }
        String sub = command == null ? "" : command.toLowerCase(java.util.Locale.ROOT);
        String target = args == null || args.isEmpty() ? null : args.get(0);
        return switch (sub) {
            case "test" -> test(target, connected);
            case "blacklist" -> blacklist(caller, callerName, target, connected);
            case "whitelist" -> whitelist(target, connected);
            case "stats" -> stats(connected);
            case "pingcolor" -> pingColor(callerName, target,
                    args != null && args.size() > 1 ? args.get(1) : null, connected);
            default -> Result.fail("Unknown subcommand '" + sub
                    + "'. Use: test | blacklist | whitelist | stats | pingcolor");
        };
    }

    /**
     * Report whether a player is using the mod and which protocol version they speak. Answers only
     * for players currently connected to this relay: that is information the relay already holds,
     * and it avoids turning the command into a way to probe arbitrary accounts.
     */
    private Result test(String name, ConnectedLookup connected) {
        if (name == null) {
            return Result.fail("Usage: /relay test <player>");
        }
        Optional<UUID> id = connected.byName(name);
        if (id.isEmpty()) {
            return Result.ok(name + " is not connected to the relay.",
                    "(Only players currently connected can be tested.)");
        }
        UUID uuid = id.get();
        String version = connected.versionOf(uuid).map(v -> "protocol v" + v).orElse("unknown version");
        String scope = connected.scopeOf(uuid).orElse("unknown");
        List<String> lines = new ArrayList<>();
        lines.add(name + " is using Relay (" + version + ").");
        lines.add("UUID: " + uuid);
        lines.add("Server: " + scope);
        if (isBanned(uuid)) {
            lines.add("This player is currently blacklisted.");
        }
        return new Result(List.copyOf(lines), false);
    }

    /**
     * Bar a player from the relay. Admins are refused outright. The target is resolved from the
     * connected sessions first (a UUID the relay verified itself) and only then via Mojang, so the
     * common case never depends on an outbound call.
     */
    private Result blacklist(UUID caller, String callerName, String name, ConnectedLookup connected) {
        if (name == null) {
            return Result.fail("Usage: /relay blacklist <player>");
        }
        UUID target;
        try {
            target = resolve(name, connected);
        } catch (NameResolver.Unavailable e) {
            return Result.fail("Could not reach Mojang to look up '" + name + "'. Try again.");
        }
        if (target == null) {
            return Result.fail("No such player: " + name);
        }
        if (isAdmin(target)) {
            return Result.fail(name + " is an administrator and cannot be blacklisted.");
        }
        if (target.equals(caller)) {
            // Covered by the admin check above, but stated explicitly so the message is useful.
            return Result.fail("You cannot blacklist yourself.");
        }
        if (!blacklist.add(target, name, callerName)) {
            return Result.fail(name + " is already blacklisted.");
        }
        LOG.info("{} ({}) blacklisted {} ({})", callerName, caller, name, target);
        return Result.ok(name + " has been blacklisted and disconnected.");
    }

    /** Lift a ban. */
    private Result whitelist(String name, ConnectedLookup connected) {
        if (name == null) {
            return Result.fail("Usage: /relay whitelist <player>");
        }
        UUID target;
        try {
            target = resolve(name, connected);
        } catch (NameResolver.Unavailable e) {
            return Result.fail("Could not reach Mojang to look up '" + name + "'. Try again.");
        }
        if (target == null) {
            return Result.fail("No such player: " + name);
        }
        if (!blacklist.remove(target)) {
            return Result.fail(name + " is not blacklisted.");
        }
        LOG.info("{} un-blacklisted ({})", name, target);
        return Result.ok(name + " has been removed from the blacklist.");
    }

    /**
     * Relay-wide counts: who is connected right now, and how many distinct players have ever
     * authenticated. The all-time figure is only as old as the counter itself — it began when this
     * version was first deployed, not when the relay was first run — so it is labelled as such
     * rather than implying it covers all history.
     */
    private Result stats(ConnectedLookup connected) {
        int online = connected.connectedCount();
        int scopes = connected.scopeCount();
        return Result.ok(
                "Relay stats:",
                "  Connected now: " + online
                        + (online == 0 ? "" : " across " + scopes + " server(s)"),
                "  Unique players (all time): " + stats.uniqueCount(),
                "  Blacklisted: " + blacklist.size(),
                "  Ping color overrides: " + pingColors.size(),
                "  Admins: " + adminStore.size());
    }

    /**
     * Force a player's ping colour, or clear the override with {@code -remove}. An override is
     * deliberately not restricted to that player's own five colours — an administrator setting it
     * is the authority — but it must still be a well-formed {@code #rrggbb} value, since it ends up
     * in every viewer's renderer.
     */
    private Result pingColor(String callerName, String name, String color,
                             ConnectedLookup connected) {
        if (name == null || color == null) {
            return Result.fail("Usage: /relay pingcolor <player> <#rrggbb|-remove>");
        }
        UUID target;
        try {
            target = resolve(name, connected);
        } catch (NameResolver.Unavailable e) {
            return Result.fail("Could not reach Mojang to look up '" + name + "'. Try again.");
        }
        if (target == null) {
            return Result.fail("No such player: " + name);
        }
        if ("-remove".equalsIgnoreCase(color.trim())) {
            return pingColors.remove(target)
                    ? Result.ok(name + "'s ping color override has been removed.",
                            "They now use their own chosen color again.")
                    : Result.fail(name + " has no ping color override.");
        }
        String normalized = PingColors.normalize(color);
        if (normalized == null) {
            return Result.fail("'" + color + "' is not a valid color. Use #rrggbb, or -remove.");
        }
        pingColors.set(target, name, normalized, callerName);
        LOG.info("{} set {}'s ping colour to {}", callerName, name, normalized);
        return Result.ok(name + "'s ping color is now " + normalized + ".",
                "This overrides whatever they pick in their own config.");
    }

    /** Connected sessions first (verified here), then Mojang for players who are offline. */
    private UUID resolve(String name, ConnectedLookup connected) throws NameResolver.Unavailable {
        Optional<UUID> live = connected.byName(name);
        if (live.isPresent()) {
            return live.get();
        }
        return names.resolve(name);
    }
}
