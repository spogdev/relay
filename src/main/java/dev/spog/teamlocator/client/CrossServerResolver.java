package dev.spog.teamlocator.client;

import dev.spog.teamlocator.TeamLocatorConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.text.Text;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Turns the raw {@code ip:port} scope carried by a cross-server alert into something the receiving
 * player is allowed to see — the name or address <em>they</em> have for that server — or decides they
 * should not be alerted at all.
 *
 * <p>Two rules, both the user's:
 * <ol>
 *   <li><b>Gate.</b> A cross-server alert is only shown if the attacker's server is one this player
 *       actually knows: an entry in their multiplayer server list, or the address in their
 *       direct-connect box. Otherwise the alert is dropped — you are not told the location of a
 *       server you have never connected to.</li>
 *   <li><b>Display.</b> Never surface the raw IPv4. Show the server-list <em>name</em>; but if that
 *       name is the vanilla placeholder ("Minecraft Server"), show the address string they saved
 *       instead. A direct-connect match shows the address they typed. The only time a bare IP is
 *       ever shown is when that IP <em>is</em> the exact string the player themselves saved or
 *       typed.</li>
 * </ol>
 *
 * <p>Matching is by resolved IP, not string equality: the attacker's scope is a resolved
 * {@code ip:port}, while the player's list holds hostnames. So each candidate address is resolved
 * to its IP set and intersected with the scope's IP set, with the port required to match too —
 * shared hosts put unrelated servers on one box distinguished only by port.
 *
 * <p>The decision engine ({@link #decide}) is pure and IP-only, taking its inputs and a DNS function
 * as parameters so it can be unit-tested without Minecraft or the network. The Minecraft-facing glue
 * — reading {@code servers.dat}, the direct-connect address, and the localized placeholder name —
 * lives in {@link #resolve} and is the only part that changes for the 1.21.11 port; each such name
 * carries a {@code // yarn:} note.
 */
@Environment(EnvType.CLIENT)
public final class CrossServerResolver {
    private CrossServerResolver() {
    }

    /** The outcome of resolving a cross-server scope against the receiver's own known servers. */
    public record Result(boolean known, String displayName) {
        static final Result UNKNOWN = new Result(false, null);

        static Result of(String displayName) {
            return new Result(true, displayName);
        }
    }

    /** One address the player knows, in the priority order it should be consulted. */
    record Candidate(Kind kind, String name, String address) {
        enum Kind { SERVER_LIST, DIRECT_CONNECT }
    }

    /**
     * Decide whether to alert for {@code scope} and, if so, what name to show, in the live client:
     * gathers the player's known servers and resolves DNS, then delegates to {@link #decide}.
     * {@link Result#known()} false means the caller should drop the alert entirely.
     *
     * <p>Does DNS and reads {@code servers.dat}; call it off the render thread.
     */
    public static Result resolve(String scope) {
        MinecraftClient mc = MinecraftClient.getInstance();
        List<Candidate> known = new ArrayList<>(serverListCandidates(mc));
        String lastDirect = lastDirectConnect(mc);
        if (lastDirect != null) {
            known.add(new Candidate(Candidate.Kind.DIRECT_CONNECT, null, lastDirect));
        }
        String placeholder = Text.translatable("selectServer.defaultName").getString();
        return decide(scope, known, placeholder, CrossServerResolver::resolveIps);
    }

    /**
     * The pure decision. Given the incoming {@code scope} ({@code ip:port}), the player's known
     * servers in priority order, the placeholder name that means "unnamed", and a DNS function,
     * returns whether to alert and what to display — with no reference to Minecraft or live network
     * state beyond the supplied {@code dns}.
     *
     * <p>Priority: a real-named server-list entry wins outright; failing that, a direct-connect
     * match shows the typed address; failing that, a placeholder-named list entry shows its saved
     * address (so we still avoid the raw IP). No match at all → {@link Result#UNKNOWN}.
     */
    static Result decide(String scope, List<Candidate> known, String placeholder,
                         Function<String, Set<String>> dns) {
        HostPort target = HostPort.parse(scope);
        if (target == null) {
            return Result.UNKNOWN;
        }
        Set<String> targetIps = resolveOrLiteral(target.host(), dns);

        String directMatch = null;
        String placeholderAddress = null;
        for (Candidate c : known) {
            HostPort hp = HostPort.parse(c.address());
            if (hp == null || !sameServer(hp, target, targetIps, dns)) {
                continue;
            }
            switch (c.kind()) {
                case SERVER_LIST -> {
                    if (isPlaceholder(c.name(), placeholder)) {
                        if (placeholderAddress == null) {
                            placeholderAddress = c.address().trim();
                        }
                    } else {
                        // A real, user-given name: the best possible label, show it and stop.
                        return Result.of(c.name().trim());
                    }
                }
                case DIRECT_CONNECT -> {
                    if (directMatch == null) {
                        directMatch = c.address().trim();
                    }
                }
            }
        }

        if (directMatch != null) {
            return Result.of(directMatch);
        }
        if (placeholderAddress != null) {
            return Result.of(placeholderAddress);
        }
        return Result.UNKNOWN;
    }

    /** Same server: same port, and at least one shared resolved IP. */
    private static boolean sameServer(HostPort candidate, HostPort target, Set<String> targetIps,
                                      Function<String, Set<String>> dns) {
        if (candidate.port() != target.port()) {
            return false;
        }
        Set<String> candidateIps = resolveOrLiteral(candidate.host(), dns);
        for (String ip : candidateIps) {
            if (targetIps.contains(ip)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The host's resolved IP set, or — if DNS returns nothing (offline, NXDOMAIN, or the host is
     * already a literal IP) — the host itself as a one-element set. That lets a saved literal-IP
     * entry match a literal-IP scope even with no working resolver.
     */
    private static Set<String> resolveOrLiteral(String host, Function<String, Set<String>> dns) {
        Set<String> ips = dns.apply(host);
        if (ips != null && !ips.isEmpty()) {
            return ips;
        }
        return Set.of(host.toLowerCase(Locale.ROOT));
    }

    private static boolean isPlaceholder(String name, String placeholder) {
        if (name == null || name.isBlank()) {
            return true;
        }
        return name.trim().equalsIgnoreCase(placeholder);
    }

    /** Host + port, port defaulted to 25565 when the address string omits it. */
    record HostPort(String host, int port) {
        static HostPort parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                // yarn: net.minecraft.client.network.ServerAddress.parse(String)
                ServerAddress addr = ServerAddress.parse(raw.trim());
                String host = addr.getAddress();
                if (host == null || host.isBlank()) {
                    return null;
                }
                return new HostPort(host.toLowerCase(Locale.ROOT), addr.getPort());
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    // ---- Minecraft-facing data gathering (the only part that differs on 1.21.11) ----

    /** Every non-LAN, non-Realm entry in the player's saved server list, in list order. */
    private static List<Candidate> serverListCandidates(MinecraftClient mc) {
        List<Candidate> out = new ArrayList<>();
        try {
            // The list is not held live on Minecraft; load it from servers.dat the way the
            // multiplayer screen does. yarn: new net.minecraft.client.option.ServerList(client),
            // then .loadFile().
            ServerList list = new ServerList(mc);
            list.loadFile();
            for (int i = 0; i < list.size(); i++) {
                ServerInfo data = list.get(i);
                if (data == null || data.address == null || data.address.isBlank()) {
                    continue;
                }
                // yarn: data.getServerType() != ServerInfo.ServerType.LAN/REALM
                if (data.getServerType() == ServerInfo.ServerType.LAN
                        || data.getServerType() == ServerInfo.ServerType.REALM) {
                    continue;
                }
                out.add(new Candidate(Candidate.Kind.SERVER_LIST, data.name, data.address.trim()));
            }
        } catch (RuntimeException e) {
            TeamLocatorConstants.LOGGER.debug("Could not read server list for cross-server match: {}",
                    e.toString());
        }
        return out;
    }

    /** The address last entered in the direct-connect box, or null. */
    private static String lastDirectConnect(MinecraftClient mc) {
        // yarn: client.options.lastServer (a String field on GameOptions).
        String last = mc.options.lastServer;
        return (last == null || last.isBlank()) ? null : last.trim();
    }

    /** All A/AAAA addresses for a host, lowercased; empty on failure (offline, NXDOMAIN, timeout). */
    private static Set<String> resolveIps(String host) {
        Set<String> ips = new HashSet<>();
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                ips.add(addr.getHostAddress().toLowerCase(Locale.ROOT));
            }
        } catch (UnknownHostException | SecurityException e) {
            // Leave empty; the decision layer treats an unresolvable host as a literal-string compare.
        }
        return ips;
    }
}
