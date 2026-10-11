package dev.spog.relayspectate.capture;

import dev.spog.relayspectate.SpectateClient;
import dev.spog.relayspectate.net.SpectateProtocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Sends a viewer the terrain the target is already standing in.
 *
 * <p>Without this a session never renders anything, and the reason is easy to miss: the capture is
 * passive, so it only forwards packets as they arrive -- but a target that has been on its server
 * for more than a few seconds has already received all its chunks, and a server does not re-send
 * chunks a client already holds. So there was nothing to forward. The viewer sat on the loading
 * screen with a working connection, a correct registry set and an empty world, waiting for terrain
 * that was never going to be sent.
 *
 * <p>It also deadlocked the position feed, which was gated on at least one chunk having been
 * forwarded -- a guard meant to stop the viewer being let into an empty world, which instead
 * guaranteed it stayed on the loading screen.
 *
 * <p>So a session starts by walking the target's own chunk cache and rebuilding a chunk packet for
 * each loaded chunk in range. From then on the passive capture keeps the viewer up to date, which
 * is what it was always for.
 */
public final class ChunkSweep {
    private ChunkSweep() {
    }

    /**
     * Rebuilds chunk packets for every loaded chunk within the capture radius of the target.
     *
     * <p>Must run on the client thread: it reads the level's chunk cache and the light engine.
     *
     * <p>Chunks are rebuilt from the client's own copy rather than replayed from a recording. The
     * client holds a {@link LevelChunk}, and the game's own packet constructor serializes one --
     * so the result is byte-identical in form to what the server sent, without this mod having to
     * have kept the original packets.
     *
     * @return the packets to send, in no particular order; chunk packets are independent
     */
    public static List<ClientboundLevelChunkWithLightPacket> loadedChunksAround(Minecraft mc) {
        List<ClientboundLevelChunkWithLightPacket> out = new ArrayList<>();
        ClientLevel level = mc.level;
        Entity player = mc.player;
        if (level == null || player == null) {
            return out;
        }
        ChunkPos center = player.chunkPosition();
        int radius = SpectateProtocol.SWEEP_RADIUS_CHUNKS;
        var lightEngine = level.getLightEngine();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = center.x() + dx;
                int z = center.z() + dz;
                // load=false: only chunks the client already has. A client cannot generate terrain,
                // so asking it to load one would do nothing useful anyway.
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                try {
                    // Empty bit sets mean "no light sections included"; the light engine argument
                    // is what the packet reads the actual light data from.
                    out.add(new ClientboundLevelChunkWithLightPacket(
                            chunk, lightEngine, new BitSet(), new BitSet()));
                } catch (RuntimeException e) {
                    // One unserializable chunk should cost its own hole in the view, not the
                    // session.
                    SpectateClient.LOGGER.debug("spectate: could not pack chunk {},{}", x, z, e);
                }
            }
        }
        SpectateClient.LOGGER.info("spectate: packed {} loaded chunks around {},{}",
                out.size(), center.x(), center.z());
        return out;
    }

    /**
     * Rebuilds spawn packets for the entities already around the target.
     *
     * <p>Needed for the same reason as the chunk sweep: entity spawns are only sent once, when an
     * entity comes into range, so a viewer joining later is told about nothing that is already
     * there -- including the target itself, which is the one entity the camera needs.
     *
     * <p>Each entity needs two packets, not one. The spawn packet carries position and type; the
     * data packet carries the synced entity data, without which a player renders with no skin,
     * no pose and no name. {@code ClientboundSetEntityDataPacket} is built from a full snapshot
     * rather than the usual dirty-only delta, since the viewer has no prior state to apply a
     * delta to.
     *
     * <p>Must run on the client thread.
     */
    public static List<Packet<?>> entitiesAround(Minecraft mc) {
        List<Packet<?>> out = new ArrayList<>();
        ClientLevel level = mc.level;
        Entity self = mc.player;
        if (level == null || self == null) {
            return out;
        }
        int blockRadius = SpectateProtocol.SWEEP_RADIUS_CHUNKS * 16;
        // Players counted separately from everything else. "Players are invisible" has been
        // diagnosed several times from the viewer's end alone, and the one fact that would have
        // localised it immediately -- whether the target even swept any other players, and
        // whether distance excluded them -- was never in either log.
        int players = 0;
        int playersOutOfRange = 0;
        int items = 0;
        int itemsWithoutStack = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof net.minecraft.world.entity.player.Player && entity != self) {
                if (entity.distanceToSqr(self.position()) > (double) blockRadius * blockRadius) {
                    playersOutOfRange++;
                } else {
                    players++;
                }
            }
            if (entity instanceof net.minecraft.world.entity.item.ItemEntity item
                    && entity.distanceToSqr(self.position())
                            <= (double) blockRadius * blockRadius) {
                items++;
                // Counted because an item entity with no stack in its synced data renders as
                // nothing at all, which is indistinguishable from the entity never arriving.
                if (item.getItem().isEmpty()) {
                    itemsWithoutStack++;
                }
            }
            if (entity == self) {
                // The target's own player entity is not in its client's entity list as something
                // to spawn for others; it is handled as the local player. It is sent below.
                continue;
            }
            if (entity.distanceToSqr(self.position()) > (double) blockRadius * blockRadius) {
                continue;
            }
            addEntity(out, entity);
        }
        // The target itself, last, so the camera has something to attach to even if the list above
        // was empty.
        addEntity(out, self);
        SpectateClient.LOGGER.info(
                "spectate: packed {} entity packets, including {} other player(s)"
                        + " ({} beyond the {}-block sweep radius) and {} dropped item(s)"
                        + " ({} with an empty stack)",
                out.size(), players, playersOutOfRange, blockRadius, items, itemsWithoutStack);
        return out;
    }

    private static void addEntity(List<Packet<?>> out, Entity entity) {
        try {
            // Spawned at its exact position, not its block position. The
            // ClientboundAddEntityPacket(Entity, int, BlockPos) convenience constructor reads
            // BlockPos.getX/getY/getZ, which are integers, so every entity arrived snapped to the
            // corner of the block it was standing in. For mobs that is a glitch that their next
            // movement packet corrects; for entities that never move it is permanent, which is why
            // text displays appeared at the wrong height and several in one block clumped onto a
            // single point. Displays carry their own transform in synced data and are never
            // repositioned by the server, so the spawn position is the only one they ever get.
            out.add(new ClientboundAddEntityPacket(
                    entity.getId(),
                    entity.getUUID(),
                    entity.getX(),
                    entity.getY(),
                    entity.getZ(),
                    entity.getXRot(),
                    entity.getYRot(),
                    entity.getType(),
                    0,
                    entity.getDeltaMovement(),
                    entity.getYHeadRot()));
            var data = entity.getEntityData().getNonDefaultValues();
            if (data != null && !data.isEmpty()) {
                out.add(new ClientboundSetEntityDataPacket(entity.getId(), data));
            }
        } catch (RuntimeException e) {
            // At warn, not debug. Three separate failures in this project were invisible because
            // the only record of them was a debug line nobody sees by default; an entity that
            // cannot be packed is exactly the kind of thing that presents as "no entities" with
            // no explanation anywhere.
            SpectateClient.LOGGER.warn("spectate: could not pack entity {}",
                    entity.getClass().getSimpleName(), e);
        }
    }
}
