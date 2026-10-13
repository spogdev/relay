package dev.spog.spegtate.capture;

import dev.spog.spegtate.SpectateClient;
import dev.spog.spegtate.net.SpectateProtocol;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayList;
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
     * client holds a {@link WorldChunk}, and the game's own packet constructor serializes one --
     * so the result is byte-identical in form to what the server sent, without this mod having to
     * have kept the original packets.
     *
     * @return the packets to send, in no particular order; chunk packets are independent
     */
    public static List<ChunkDataS2CPacket> loadedChunksAround(MinecraftClient mc) {
        List<ChunkDataS2CPacket> out = new ArrayList<>();
        ClientWorld level = mc.world;
        Entity player = mc.player;
        if (level == null || player == null) {
            return out;
        }
        ChunkPos center = player.getChunkPos();
        int radius = SpectateProtocol.SWEEP_RADIUS_CHUNKS;
        var lightEngine = level.getLightingProvider();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = center.x + dx;
                int z = center.z + dz;
                // load=false: only chunks the client already has. A client cannot generate terrain,
                // so asking it to load one would do nothing useful anyway.
                WorldChunk chunk = level.getChunkManager().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                try {
                    // null, not an empty BitSet. These two arguments are section
                    // filters and the test inside is `mask == null || mask.get(i)`,
                    // so null includes every section while an empty set includes
                    // none of them. Passing empty stripped the light out of every
                    // swept chunk and left the viewer lighting them from blocks
                    // alone, with no skylight -- which is what made a swept world
                    // look so much darker than the target's own.
                    out.add(new ChunkDataS2CPacket(
                            chunk, lightEngine, null, null));
                } catch (RuntimeException e) {
                    // One unserializable chunk should cost its own hole in the view, not the
                    // session.
                    SpectateClient.LOGGER.debug("spectate: could not pack chunk {},{}", x, z, e);
                }
            }
        }
        return out;
    }

    /**
     * Rebuilds chunk packets for a named list of chunks, for the repair loop.
     *
     * <p>Answers a viewer that has found holes in its terrain. Unlike the sweep above this is not
     * bounded by a radius -- the caller names the chunks -- but it is bounded by what this client
     * actually has: a chunk this client has itself unloaded cannot be rebuilt, and is skipped
     * rather than fetched, because a client cannot ask its server for one chunk.
     *
     * <p>Must run on the client thread: it reads the chunk cache and the light engine.
     *
     * @param wanted chunk positions as {@code [x, z]} pairs
     * @return packets for those of them this client holds
     */
    public static List<ChunkDataS2CPacket> chunksAt(MinecraftClient mc,
                                                                      List<ChunkPos> wanted) {
        List<ChunkDataS2CPacket> out = new ArrayList<>();
        ClientWorld level = mc.world;
        if (level == null || wanted.isEmpty()) {
            return out;
        }
        var lightEngine = level.getLightingProvider();
        for (ChunkPos pos : wanted) {
            WorldChunk chunk = level.getChunkManager()
                    .getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
            if (chunk == null) {
                continue;
            }
            try {
                // null sends all light sections; see loadedChunksAround.
                out.add(new ChunkDataS2CPacket(
                        chunk, lightEngine, null, null));
            } catch (RuntimeException e) {
                SpectateClient.LOGGER.debug("spectate: could not repack chunk {},{}",
                        pos.x, pos.z, e);
            }
        }
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
     * no pose and no name. {@code EntityTrackerUpdateS2CPacket} is built from a full snapshot
     * rather than the usual dirty-only delta, since the viewer has no prior state to apply a
     * delta to.
     *
     * <p>Must run on the client thread.
     */
    public static List<Packet<?>> entitiesAround(MinecraftClient mc) {
        List<Packet<?>> out = new ArrayList<>();
        ClientWorld level = mc.world;
        Entity self = mc.player;
        if (level == null || self == null) {
            return out;
        }
        int blockRadius = SpectateProtocol.SWEEP_RADIUS_CHUNKS * 16;
        double maxDistSqr = (double) blockRadius * blockRadius;
        for (Entity entity : level.getEntities()) {
            if (entity == self) {
                // The target's own player entity is not in its client's entity list as something
                // to spawn for others; it is handled as the local player. It is sent below.
                continue;
            }
            if (entity.squaredDistanceTo(self.getEntityPos()) > maxDistSqr) {
                continue;
            }
            addEntity(out, entity);
        }
        // The target itself, last, so the camera has something to attach to even if the list above
        // was empty.
        addEntity(out, self);
        return out;
    }

    private static void addEntity(List<Packet<?>> out, Entity entity) {
        try {
            // Spawned at its exact position, not its block position. The
            // EntitySpawnS2CPacket(Entity, int, BlockPos) convenience constructor reads
            // BlockPos.getX/getY/getZ, which are integers, so every entity arrived snapped to the
            // corner of the block it was standing in. For mobs that is a glitch that their next
            // movement packet corrects; for entities that never move it is permanent, which is why
            // text displays appeared at the wrong height and several in one block clumped onto a
            // single point. Displays carry their own transform in synced data and are never
            // repositioned by the server, so the spawn position is the only one they ever get.
            out.add(new EntitySpawnS2CPacket(
                    entity.getId(),
                    entity.getUuid(),
                    entity.getX(),
                    entity.getY(),
                    entity.getZ(),
                    entity.getPitch(),
                    entity.getYaw(),
                    entity.getType(),
                    0,
                    entity.getVelocity(),
                    entity.getHeadYaw()));
            addEquipment(out, entity);
            var data = entity.getDataTracker().getChangedEntries();
            if (data != null && !data.isEmpty()) {
                out.add(new EntityTrackerUpdateS2CPacket(entity.getId(), data));
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

    /**
     * Seeds one entity's current equipment, if it has any.
     *
     * <p>Equipment updates are forwarded live, so a change during a session already reached a
     * viewer. The gap was everything worn <em>before</em> it: a server sends this packet only
     * when equipment changes, so for anyone already standing there in armour it had been sent
     * long ago and was never coming again. That is the same "sent exactly once" problem the
     * chunk and spawn sweeps exist to solve, and it is solved the same way.
     *
     * <p>Empty slots are skipped rather than sent as empty stacks: a freshly spawned entity
     * already has nothing in them, so sending them would be a packet that changes nothing, and
     * for a crowd of players that is the majority of the slots.
     */
    private static void addEquipment(List<Packet<?>> out, Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        List<com.mojang.datafixers.util.Pair<EquipmentSlot, ItemStack>> worn =
                new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = living.getEquippedStack(slot);
            if (!stack.isEmpty()) {
                worn.add(com.mojang.datafixers.util.Pair.of(slot, stack.copy()));
            }
        }
        if (!worn.isEmpty()) {
            out.add(new EntityEquipmentUpdateS2CPacket(entity.getId(), worn));
        }
    }
}
