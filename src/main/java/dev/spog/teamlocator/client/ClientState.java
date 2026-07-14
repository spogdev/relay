package dev.spog.teamlocator.client;

import dev.spog.teamlocator.net.PositionSnapshotPayload;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side view state: the latest position snapshot from the server and the set of players
 * currently flashing red (they signalled they are under attack). Read by the HUD each frame,
 * written by the network receivers on the client thread.
 */
public final class ClientState {
    /** How long a player stays red after an attack ping, in milliseconds. */
    private static final long ATTACK_FLASH_MS = 5_000L;

    private static volatile List<PositionSnapshotPayload.PlayerPos> latest = List.of();
    private static final Map<UUID, Long> attackUntil = new ConcurrentHashMap<>();

    private ClientState() {
    }

    public static void setLatest(List<PositionSnapshotPayload.PlayerPos> entries) {
        latest = entries;
    }

    public static List<PositionSnapshotPayload.PlayerPos> latest() {
        return latest;
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
        latest = List.of();
        attackUntil.clear();
    }
}
