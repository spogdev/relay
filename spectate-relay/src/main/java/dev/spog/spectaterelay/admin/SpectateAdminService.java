package dev.spog.spectaterelay.admin;

import dev.spog.teamlocator.relay.admin.AdminStore;
import dev.spog.teamlocator.relay.admin.MojangNameResolver;
import dev.spog.teamlocator.relay.admin.NameResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Access control and the {@code /spegtate} subcommands for the spectate relay.
 *
 * <p><b>Deny by default.</b> This mod is private, so a player who is not on the whitelist is
 * refused at authentication. The main relay's moderation is the other way round -- everyone is
 * allowed until blacklisted -- so the two services deliberately do not share a store.
 *
 * <p><b>Administrators come from the main relay's {@code admins.json}</b>, read through the same
 * {@link AdminStore}. One list governs both services: someone who can moderate Relay can moderate
 * Spegtate, and there is only one file to edit. Like there, admin is fixed at startup and granting
 * it needs a file edit plus a restart rather than anything reachable over the network.
 *
 * <p><b>Admins always bypass the whitelist.</b> Without that rule a corrupt or accidentally
 * emptied {@code whitelist.json} would lock out every administrator along with everyone else,
 * leaving no way to fix it except console access to the VPS. This is the one escape hatch and it
 * is deliberate.
 *
 * <p>Authorization is by Mojang-verified UUID only, never by anything the client sent, so a
 * patched client cannot claim to be an admin.
 */
public final class SpectateAdminService {
    private static final Logger LOG = LoggerFactory.getLogger(SpectateAdminService.class);

    /** What a command produced: lines to show the caller, and whether it failed. */
    public record Result(List<String> lines, boolean error) {
        public static Result ok(String... lines) {
            return new Result(List.of(lines), false);
        }

        public static Result fail(String line) {
            return new Result(List.of(line), true);
        }
    }

    /**
     * Live state the service cannot see for itself, supplied by the relay server.
     *
     * <p>An interface rather than a back-reference so the command logic can be tested without
     * standing up a WebSocket server.
     */
    public interface LiveState {
        /** The verified UUID of a connected player with this name, or empty. */
        Optional<UUID> byName(String name);

        /** How many players are authenticated right now. */
        int onlineCount();

        /** How many viewers are watching someone at this moment. */
        int spectatingCount();

        /** Everyone authenticated right now, as display names, in no particular order. */
        List<String> onlineNames();
    }

    private final AdminStore admins;
    private final WhitelistStore whitelist;
    private final WhitelistState state;
    private final SeenStore seen;
    private final NameResolver names;

    private SpectateAdminService(AdminStore admins, WhitelistStore whitelist,
                                 WhitelistState state, SeenStore seen, NameResolver names) {
        this.admins = admins;
        this.whitelist = whitelist;
        this.state = state;
        this.seen = seen;
        this.names = names;
    }

    /**
     * Load every store from {@code dataDir}.
     *
     * <p>{@code admins.json} is read from the same directory the main relay uses, so running both
     * services with the same data directory gives them one shared admin list -- which is the
     * intended deployment.
     */
    public static SpectateAdminService load(Path dataDir) {
        return new SpectateAdminService(
                AdminStore.load(dataDir.resolve("admins.json")),
                WhitelistStore.load(dataDir.resolve("whitelist.json")),
                WhitelistState.load(dataDir.resolve("whitelist-state.json")),
                SeenStore.load(dataDir.resolve("seen.json")),
                new MojangNameResolver());
    }

    /** Test seam: explicit stores and a stubbed name resolver, no disk or network. */
    public static SpectateAdminService of(AdminStore admins, WhitelistStore whitelist,
                                          WhitelistState state, SeenStore seen,
                                          NameResolver names) {
        return new SpectateAdminService(admins, whitelist, state, seen, names);
    }

    public boolean isAdmin(UUID player) {
        return admins.isAdmin(player);
    }

    /**
     * Whether this player may use the mod at all.
     *
     * <p>Admins always pass, and so does everyone when enforcement is switched off. Otherwise
     * membership of the whitelist decides it.
     */
    public boolean mayConnect(UUID player) {
        if (player == null) {
            return false;
        }
        return admins.isAdmin(player) || !state.enabled() || whitelist.isWhitelisted(player);
    }

    /**
     * Note that a player authenticated, for the all-time unique count.
     *
     * <p>Called before the access check, so the figure counts everyone who has the mod rather than
     * everyone who was let in.
     */
    public void recordSeen(UUID player) {
        seen.record(player);
    }

    /**
     * Run a subcommand on behalf of {@code caller}. The caller's admin status is re-checked here
     * rather than trusted from the call site.
     */
    public Result execute(UUID caller, String callerName, String command, List<String> args,
                          LiveState live) {
        if (!isAdmin(caller)) {
            // Same message whatever the reason: an unprivileged caller learns nothing about
            // whether this relay has moderation configured at all.
            return Result.fail("You do not have permission to use this command");
        }
        String sub = command == null ? "" : command.toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "whitelist" -> whitelist(callerName, args);
            case "stats" -> stats(live);
            case "playerlist" -> playerlist(live);
            default -> Result.fail("Unknown subcommand '" + sub
                    + "'. Use: whitelist | stats | playerlist");
        };
    }

    // ---- whitelist ----

    private Result whitelist(String callerName, List<String> args) {
        String action = args == null || args.isEmpty()
                ? "" : args.get(0).toLowerCase(Locale.ROOT);
        String target = args != null && args.size() > 1 ? args.get(1) : null;
        return switch (action) {
            case "list" -> whitelistList();
            case "add" -> whitelistAdd(callerName, target);
            case "remove" -> whitelistRemove(target);
            case "on" -> whitelistToggle(true);
            case "off" -> whitelistToggle(false);
            default -> Result.fail(
                    "Usage: /spegtate whitelist <list|add|remove|on|off> [player]");
        };
    }

    /**
     * The whole list, plus whether it is actually being enforced.
     *
     * <p>The enforcement state is included because the list is retained while disabled: without
     * this line an admin reading a populated list has no way to tell that none of it is in effect.
     */
    private Result whitelistList() {
        List<WhitelistStore.Listed> all = whitelist.all();
        List<String> lines = new ArrayList<>();
        lines.add("Whitelist (" + all.size() + ") -- enforcement is "
                + (state.enabled() ? "on" : "off"));
        if (all.isEmpty()) {
            lines.add("Nobody is whitelisted. Only admins can connect");
        }
        for (WhitelistStore.Listed e : all) {
            String name = e.name() == null || e.name().isBlank() ? e.uuid().toString() : e.name();
            lines.add(e.addedBy() == null || e.addedBy().isBlank()
                    ? "- " + name
                    : "- " + name + " (added by " + e.addedBy() + ")");
        }
        return new Result(List.copyOf(lines), false);
    }

    /**
     * Grant access by name.
     *
     * <p>The name is resolved through Mojang rather than taken at face value, because the whole
     * store is keyed by UUID -- whitelisting a string would grant access to whoever holds that
     * name today and silently revoke it on a rename. An unreachable Mojang is reported as "try
     * again" rather than "no such player", so an outage is not mistaken for a typo.
     */
    private Result whitelistAdd(String callerName, String name) {
        if (name == null || name.isBlank()) {
            return Result.fail("Usage: /spegtate whitelist add <player>");
        }
        UUID id;
        try {
            id = names.resolve(name);
        } catch (NameResolver.Unavailable e) {
            return Result.fail("Could not reach Mojang to look up '" + name + "'. Try again");
        }
        if (id == null) {
            return Result.fail("No Minecraft account named '" + name + "'");
        }
        if (!whitelist.add(id, name, callerName)) {
            return Result.fail(name + " is already whitelisted");
        }
        LOG.info("{} whitelisted {} ({})", callerName, name, id);
        return Result.ok("Added " + name + " to the whitelist");
    }

    /**
     * Revoke access by name.
     *
     * <p>Resolved the same way as {@code add}, with one addition: if Mojang cannot be reached the
     * stored names are searched, so access can still be revoked during an outage. Removal is the
     * safety-critical direction -- being unable to revoke is worse than being unable to grant.
     */
    private Result whitelistRemove(String name) {
        if (name == null || name.isBlank()) {
            return Result.fail("Usage: /spegtate whitelist remove <player>");
        }
        UUID id = null;
        try {
            id = names.resolve(name);
        } catch (NameResolver.Unavailable e) {
            for (WhitelistStore.Listed e2 : whitelist.all()) {
                if (name.equalsIgnoreCase(e2.name())) {
                    id = e2.uuid();
                    break;
                }
            }
            if (id == null) {
                return Result.fail("Could not reach Mojang, and nobody named '" + name
                        + "' is recorded on the whitelist");
            }
        }
        if (id == null) {
            return Result.fail("No Minecraft account named '" + name + "'");
        }
        if (!whitelist.remove(id)) {
            return Result.fail(name + " is not on the whitelist");
        }
        LOG.info("removed {} ({}) from the whitelist", name, id);
        return Result.ok("Removed " + name + " from the whitelist");
    }

    /**
     * Switch enforcement on or off. The list itself is untouched either way, which the reply says
     * explicitly so nobody fears that "off" discarded it.
     */
    private Result whitelistToggle(boolean on) {
        if (!state.set(on)) {
            return Result.fail("The whitelist is already " + (on ? "on" : "off"));
        }
        return on
                ? Result.ok("Whitelist enabled. Only whitelisted players can use the mod")
                : Result.ok("Whitelist disabled. Anyone can use the mod",
                        "The list of " + whitelist.size() + " player(s) has been kept");
    }

    // ---- stats ----

    private Result stats(LiveState live) {
        return new Result(List.of(
                "Online Players: " + live.onlineCount(),
                "",
                "Unique Players (All Time): " + seen.uniqueCount(),
                "Currently Spectating: " + live.spectatingCount()), false);
    }

    // ---- playerlist ----

    /**
     * Everyone online with the mod.
     *
     * <p>Returned as one line per player and left unpaginated here: paging is a presentation
     * concern and the client does it, because only the client knows how much chat is on screen
     * and can make the page links clickable.
     */
    private Result playerlist(LiveState live) {
        List<String> online = live.onlineNames();
        if (online.isEmpty()) {
            return Result.ok("Nobody is online with Spegtate");
        }
        List<String> lines = new ArrayList<>(online.size() + 1);
        lines.add("Online with Spegtate (" + online.size() + "):");
        List<String> sorted = new ArrayList<>(online);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        lines.addAll(sorted);
        return new Result(List.copyOf(lines), false);
    }
}
