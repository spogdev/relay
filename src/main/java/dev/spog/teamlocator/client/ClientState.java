package dev.spog.teamlocator.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side view state: the last known position of each teammate. Written by the relay client's
 * receiver thread, read by the HUD each frame — all containers are concurrent.
 *
 * <p>The relay fans positions out one player at a time, so entries are merged per-UUID rather than
 * replaced wholesale, and an entry expires if its player stops reporting (logged off, untrusted us,
 * or lost connection) so stale coordinates never linger on the HUD.
 */
public final class ClientState {

    /** Drop a teammate from the HUD if we haven't heard a position for this long. */
    private static final long POSITION_EXPIRE_MS = 10_000L;

    /**
     * How far behind the newest snapshot the interpolated position is rendered, in milliseconds.
     * Positions arrive at ~5 Hz (one every ~200 ms), so rendering one tick in the past means we
     * are almost always interpolating between two snapshots we already have rather than guessing
     * ahead of the newest one. The cost is a constant ~200 ms lag behind the true latest position,
     * which is imperceptible on a map and far preferable to the icon teleporting each tick.
     *
     * @see #interpolated(UUID)
     */
    private static final long RENDER_DELAY_MS = 200L;

    private record Timestamped(TrackedPos pos, long atMillis) {
    }

    /** The current and immediately-previous snapshot for one player, for interpolation. */
    private record Interp(TrackedPos prev, long prevAt, TrackedPos cur, long curAt) {
    }

    private static final Map<UUID, Timestamped> positions = new ConcurrentHashMap<>();
    /** Two-point history per player; only the interpolating readers (world map) consult it. */
    private static final Map<UUID, Interp> interp = new ConcurrentHashMap<>();

    private ClientState() {
    }

    /** Merge one player's freshly-reported position. */
    public static void updatePosition(TrackedPos pos) {
        long now = System.currentTimeMillis();
        positions.compute(pos.id(), (id, existing) -> {
            // Reuse the existing TrackedPos instance when the position is unchanged, so downstream
            // identity checks (Xaero's element reuse compares the player object by ==) stay stable
            // and don't rebuild/flicker the tracker element every update.
            if (existing != null && existing.pos().equals(pos)) {
                return new Timestamped(existing.pos(), now);
            }
            return new Timestamped(pos, now);
        });
        // Keep a two-point history so the world-map reader can interpolate. Only advance the window
        // when the position actually moved; a repeat snapshot (same coords) would otherwise collapse
        // prev and cur onto the same point and stall the glide.
        interp.compute(pos.id(), (id, existing) -> {
            if (existing == null) {
                return new Interp(pos, now, pos, now);
            }
            if (samePlace(existing.cur(), pos)) {
                // No movement: refresh the current timestamp but keep the window open, so a player
                // standing still doesn't leave a stale prev that we'd later lerp away from.
                return new Interp(existing.prev(), existing.prevAt(), pos, now);
            }
            return new Interp(existing.cur(), existing.curAt(), pos, now);
        });
    }

    private static boolean samePlace(TrackedPos a, TrackedPos b) {
        return a.x() == b.x() && a.y() == b.y() && a.z() == b.z()
                && a.dimension().equals(b.dimension());
    }

    /**
     * The player's position interpolated at {@code now - RENDER_DELAY_MS}, so a moving teammate's
     * map icon glides between the ~5 Hz snapshots instead of teleporting each tick — which is what
     * made a hovered icon's zoom oscillate as the hit-test flipped on the jumps. Returns the raw
     * latest position (no smoothing) when there is only one snapshot, when the two straddle a
     * dimension change (lerping across a portal would slide the icon through the void), or when the
     * player isn't tracked. Never extrapolates past the newest snapshot.
     */
    public static double[] interpolated(UUID id) {
        Interp w = interp.get(id);
        if (w == null) {
            Timestamped t = positions.get(id);
            return t == null ? null : new double[] {t.pos().x(), t.pos().y(), t.pos().z()};
        }
        TrackedPos prev = w.prev();
        TrackedPos cur = w.cur();
        if (prev == cur || !prev.dimension().equals(cur.dimension()) || w.curAt() <= w.prevAt()) {
            return new double[] {cur.x(), cur.y(), cur.z()};
        }
        long renderTime = System.currentTimeMillis() - RENDER_DELAY_MS;
        if (renderTime <= w.prevAt()) {
            return new double[] {prev.x(), prev.y(), prev.z()};
        }
        if (renderTime >= w.curAt()) {
            return new double[] {cur.x(), cur.y(), cur.z()};
        }
        double t = (double) (renderTime - w.prevAt()) / (double) (w.curAt() - w.prevAt());
        return new double[] {
                prev.x() + (cur.x() - prev.x()) * t,
                prev.y() + (cur.y() - prev.y()) * t,
                prev.z() + (cur.z() - prev.z()) * t,
        };
    }

    /**
     * Current non-stale positions in a stable order for HUD rendering, less anyone muted. A mute
     * hides a player everywhere -- the HUD, markers, routes and maps all read from here -- not only
     * their pings.
     */
    public static List<TrackedPos> latest() {
        long now = System.currentTimeMillis();
        Set<UUID> muted = TeamLocatorClient.CONFIG != null
                ? TeamLocatorClient.CONFIG.mutedPingSet() : Set.of();
        List<TrackedPos> out = new ArrayList<>();
        for (Map.Entry<UUID, Timestamped> e : positions.entrySet()) {
            if (now - e.getValue().atMillis() > POSITION_EXPIRE_MS) {
                positions.remove(e.getKey(), e.getValue());
                interp.remove(e.getKey());
            } else if (!muted.contains(e.getKey())) {
                out.add(e.getValue().pos());
            }
        }
        out.sort(Comparator.comparing(TrackedPos::id));
        return out;
    }

    /**
     * {@link #latest()}, less anyone on another server of the network -- for everything that draws
     * teammates in the world, where their coordinates would point at somewhere else entirely.
     */
    public static List<TrackedPos> onServer(String ourWorld) {
        List<TrackedPos> out = latest();
        out.removeIf(p -> !p.onServer(ourWorld));
        return out;
    }

    /** Forget one player's position at once, rather than waiting for it to expire. */
    public static void remove(UUID player) {
        positions.remove(player);
        interp.remove(player);
    }

    /** Clear everything on disconnect so stale positions don't linger into the next session. */
    public static void reset() {
        positions.clear();
        interp.clear();
    }
}
