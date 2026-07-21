package dev.spog.teamlocator.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The location pings currently visible to this client, one per player.
 *
 * <p>A new ping from a player replaces their previous one outright — the mod deliberately shows at
 * most one marker per teammate, so a second press moves the marker rather than littering the world.
 *
 * <p>Expiry is <em>viewer-side</em>: how long a ping stays up is each player's own setting, so two
 * people can see the same ping for different durations. That also means the relay never has to
 * track or retract anything — it fans the ping out once and forgets it.
 *
 * <p>Written by the relay receive thread, read by the renderer each frame; the map is concurrent.
 */
@Environment(EnvType.CLIENT)
public final class PingState {
    /** One teammate's marker: where it is, what colour, and when it arrived. */
    public record Ping(UUID owner, double x, double y, double z, Identifier dimension,
                       int argb, long placedAtMillis) {
    }

    private static final Map<UUID, Ping> pings = new ConcurrentHashMap<>();

    private PingState() {
    }

    /** Record a teammate's ping, replacing whatever they had before. */
    public static void put(Ping ping) {
        pings.put(ping.owner(), ping);
    }

    /** Drop a specific player's ping (used when they are muted after the fact). */
    public static void remove(UUID owner) {
        pings.remove(owner);
    }

    /**
     * The pings that should be drawn right now: not expired per the viewer's configured lifetime,
     * not from a muted player, and in the viewer's current dimension (a marker in another dimension
     * would point at unrelated coordinates).
     *
     * <p>Expired entries are dropped as they are found, so this doubles as the cleanup pass.
     */
    public static List<Ping> visible(Identifier viewerDimension, long lifetimeMillis,
                                     java.util.Set<UUID> muted) {
        long now = System.currentTimeMillis();
        List<Ping> out = new ArrayList<>();
        for (Map.Entry<UUID, Ping> e : pings.entrySet()) {
            Ping p = e.getValue();
            if (now - p.placedAtMillis() > lifetimeMillis) {
                pings.remove(e.getKey(), p);
                continue;
            }
            if (muted.contains(p.owner())) {
                continue;
            }
            if (viewerDimension != null && !viewerDimension.equals(p.dimension())) {
                continue;
            }
            out.add(p);
        }
        return out;
    }

    /** How far through its life a ping is, 0 (fresh) to 1 (expired), for fade-out. */
    public static float age(Ping ping, long lifetimeMillis) {
        if (lifetimeMillis <= 0) {
            return 1.0f;
        }
        long elapsed = System.currentTimeMillis() - ping.placedAtMillis();
        return Math.max(0.0f, Math.min(1.0f, elapsed / (float) lifetimeMillis));
    }

    /** Clear everything on disconnect so stale markers never linger into the next session. */
    public static void reset() {
        pings.clear();
    }
}
