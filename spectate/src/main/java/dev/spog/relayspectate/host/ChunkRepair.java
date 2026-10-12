package dev.spog.relayspectate.host;

import dev.spog.relayspectate.net.SpectateProtocol;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds terrain the viewer never received and asks the target to resend it.
 *
 * <p>Repair rather than prevention, which is a deliberate change of approach. A chunk is sent
 * once: the server does not resend a chunk the target already has, and the capture does not
 * resend one it has already forwarded. So any chunk lost anywhere along the path -- the radius
 * filter rejecting it at a moment the target was moving, the cache centre being briefly elsewhere
 * when it landed, a frame dropped on either socket, a decode failure -- leaves a permanent hole
 * that nothing in the system will ever fill. Several rounds of fixes have narrowed the ways a
 * chunk can be lost without closing them all, because some are not reachable from either end.
 *
 * <p>The asymmetry that makes this work: only the viewer knows what the viewer has. The target
 * sees what it sent, not what arrived; the service forwards bytes without reading them. But only
 * the target can answer, and it can answer without involving its server at all, because its own
 * client holds the chunk data and the packet constructor will serialize any chunk from it. So the
 * viewer names its holes and the target fills them.
 *
 * <p>What counts as a hole is narrower than "not loaded", and the distinction matters. A chunk
 * inside the streamed radius that the viewer lacks is a hole. A chunk it lacks because it is
 * beyond what the target ever streams is not -- asking for that would produce a request every
 * sweep that the target cannot satisfy. So requests are confined to the region the capture
 * actually sends, measured from the target rather than from the camera.
 */
public final class ChunkRepair {
    /**
     * Ticks between sweeps. At 20 ticks a second, once every two seconds.
     *
     * <p>Slow on purpose. A chunk in flight is not a hole, and the round trip runs target to
     * service to viewer with a terrain backlog drained at a few frames per tick, so a chunk can
     * legitimately be tens of ticks from arriving. Sweeping faster would mostly re-request chunks
     * already on their way, and the answers would compete with the live stream for the same budget.
     */
    private static final int SWEEP_INTERVAL_TICKS = 40;

    /**
     * Ticks before the same chunk may be asked for again.
     *
     * <p>Without this a chunk the target cannot supply -- one its own client has unloaded, say --
     * would be requested on every sweep for the life of the session. Five seconds is comfortably
     * longer than a round trip, so a chunk that is merely slow is not asked for twice.
     */
    private static final int REASK_COOLDOWN_TICKS = 100;

    /** Chunks asked for, and the tick they were asked on. */
    private final Map<Long, Integer> asked = new HashMap<>();

    private int ticks;

    /**
     * Returns chunks to request, or an empty list when there is nothing to ask for.
     *
     * <p>Called once per client tick; does its own rate limiting so the caller need not.
     *
     * @param targetX the last reported target x, which centres the region worth asking about
     * @param targetZ the last reported target z
     */
    public List<ChunkPos> sweep(double targetX, double targetZ) {
        ticks++;
        if (ticks % SWEEP_INTERVAL_TICKS != 0) {
            return List.of();
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld level = mc.world;
        if (level == null) {
            return List.of();
        }
        Entity camera = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        if (camera == null) {
            return List.of();
        }
        int centreX = net.minecraft.util.math.ChunkSectionPos.getSectionCoord(targetX);
        int centreZ = net.minecraft.util.math.ChunkSectionPos.getSectionCoord(targetZ);
        // Confined to what the capture actually streams. A chunk outside this was never going to
        // be sent, so treating it as a hole would mean asking forever for terrain that does not
        // exist as far as this session is concerned.
        int radius = SpectateProtocol.CAPTURE_RADIUS_CHUNKS;
        ChunkPos cameraChunk = camera.getChunkPos();
        int keep = SpectateProtocol.VIEWER_RADIUS_CHUNKS;

        List<ChunkPos> missing = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (missing.size() >= SpectateProtocol.MAX_CHUNK_REQUESTS) {
                    break;
                }
                int x = centreX + dx;
                int z = centreZ + dz;
                // Only chunks this client would keep if they arrived. The cache discards anything
                // beyond its view radius of its centre, which is the camera, so asking for a chunk
                // outside that would have it answered and then thrown away again.
                if (Math.abs(x - cameraChunk.x) > keep
                        || Math.abs(z - cameraChunk.z) > keep) {
                    continue;
                }
                // load=false: this asks what the client has and must never cause it to make one.
                if (level.getChunkManager().getChunk(x, z, ChunkStatus.FULL, false) != null) {
                    continue;
                }
                long key = key(x, z);
                Integer when = asked.get(key);
                if (when != null && ticks - when < REASK_COOLDOWN_TICKS) {
                    continue;
                }
                asked.put(key, ticks);
                missing.add(new ChunkPos(x, z));
            }
        }
        // Expired entries dropped, so a session that walks a long way does not accumulate one
        // entry per chunk it ever passed through.
        asked.entrySet().removeIf(e -> ticks - e.getValue() > REASK_COOLDOWN_TICKS);
        return missing;
    }

    /**
     * Notes that a requested chunk arrived, so it is not asked for again.
     *
     * <p>Called before the packet is handed on, so a chunk that arrives and is then rejected by
     * the cache still counts as answered -- otherwise one the target supplied but the viewer
     * discarded would be requested forever.
     */
    public void onChunkArrived(int x, int z) {
        asked.remove(key(x, z));
    }

    /**
     * Packs a chunk position into a map key.
     *
     * <p>Written out rather than using the game's own packer, whose name differs between the
     * mapping sets this mod is built against and which is not worth a per-version shim for two
     * lines of arithmetic.
     */
    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

}
