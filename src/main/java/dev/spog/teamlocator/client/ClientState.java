package dev.spog.teamlocator.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side view state: the last known position of each teammate and the set of players
 * currently flashing red (they signalled they are under attack). Written by the relay client's
 * receiver thread, read by the HUD each frame — all containers are concurrent.
 *
 * <p>The relay fans positions out one player at a time, so entries are merged per-UUID rather than
 * replaced wholesale, and an entry expires if its player stops reporting (logged off, untrusted us,
 * or lost connection) so stale coordinates never linger on the HUD.
 */
public final class ClientState {
    /** How long a player stays red after an attack ping, in milliseconds. */
    private static final long ATTACK_FLASH_MS = 5_000L;

    /** Drop a teammate from the HUD if we haven't heard a position for this long. */
    private static final long POSITION_EXPIRE_MS = 10_000L;

    private record Timestamped(TrackedPos pos, long atMillis) {
    }

    private static final Map<UUID, Timestamped> positions = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> attackUntil = new ConcurrentHashMap<>();

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
    }

    /** Current non-stale positions in a stable order for HUD rendering. */
    public static List<TrackedPos> latest() {
        long now = System.currentTimeMillis();
        List<TrackedPos> out = new ArrayList<>();
        for (Map.Entry<UUID, Timestamped> e : positions.entrySet()) {
            if (now - e.getValue().atMillis() > POSITION_EXPIRE_MS) {
                positions.remove(e.getKey(), e.getValue());
            } else {
                out.add(e.getValue().pos());
            }
        }
        out.sort(Comparator.comparing(TrackedPos::id));
        return out;
    }

    public static void flagAttacked(UUID player) {
        attackUntil.put(player, System.currentTimeMillis() + ATTACK_FLASH_MS);
    }

    public static boolean isUnderAttack(UUID player) {
        Long until = attackUntil.get(player);
        return until != null && System.currentTimeMillis() < until;
    }

    /** Clear everything on disconnect so stale positions don't linger into the next session. */
    public static void reset() {
        positions.clear();
        attackUntil.clear();
    }
}
