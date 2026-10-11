package dev.spog.relayspectate.capture;

import dev.spog.relayspectate.net.SpectateProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveMinecartPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.world.level.ChunkPos;

import java.util.Set;

/**
 * Decides which inbound packets a spectate session forwards, and clips terrain to the capture
 * radius.
 *
 * <p>An allow-list, not a block-list. The target's inbound stream contains its inventory, its
 * private messages, its advancement and statistics progress and its own screen state; none of that
 * belongs to a viewer, and a block-list would leak any of it that a future version adds under a new
 * packet class. Listing what a viewer needs means an unrecognised packet is withheld by default.
 *
 * <p>What is deliberately absent, and why:
 * <ul>
 *   <li>Container and inventory packets -- the target's items are private.</li>
 *   <li>Chat of every kind -- the target's messages, including DMs, are private. A viewer who
 *       wants to talk has Relay's own chat.</li>
 *   <li>Advancements, statistics, recipes -- the target's account progress, and useless to a
 *       viewer.</li>
 *   <li>{@code ClientboundLoginPacket} and the configuration-phase packets -- those are replayed
 *       once as the session handshake rather than forwarded mid-stream, so a mid-session respawn
 *       does not re-trigger a login on the viewer.</li>
 *   <li>{@code ClientboundSetHealthPacket}, abilities and held slot -- these describe the
 *       <em>receiving</em> player, and applying them to the viewer's own local player would fight
 *       the viewer's spectator state.</li>
 * </ul>
 */
public final class CaptureFilter {
    private CaptureFilter() {
    }

    /**
     * Packet classes a viewer needs to render the target's surroundings: terrain, entities and
     * their movement, and the ambient level state that makes it look right.
     */
    private static final Set<Class<?>> FORWARDED = Set.of(
            // Terrain.
            ClientboundLevelChunkWithLightPacket.class,
            ClientboundForgetLevelChunkPacket.class,
            ClientboundLightUpdatePacket.class,
            ClientboundChunksBiomesPacket.class,
            ClientboundChunkBatchStartPacket.class,
            ClientboundChunkBatchFinishedPacket.class,
            ClientboundBlockUpdatePacket.class,
            ClientboundBlockEntityDataPacket.class,
            ClientboundBlockEventPacket.class,
            ClientboundBlockDestructionPacket.class,
            // Entities.
            ClientboundAddEntityPacket.class,
            ClientboundRemoveEntitiesPacket.class,
            ClientboundSetEntityDataPacket.class,
            ClientboundSetEntityMotionPacket.class,
            ClientboundSetEntityLinkPacket.class,
            ClientboundSetEquipmentPacket.class,
            ClientboundSetPassengersPacket.class,
            ClientboundMoveEntityPacket.Pos.class,
            ClientboundMoveEntityPacket.PosRot.class,
            ClientboundMoveEntityPacket.Rot.class,
            ClientboundMoveMinecartPacket.class,
            ClientboundMoveVehiclePacket.class,
            ClientboundTeleportEntityPacket.class,
            ClientboundEntityPositionSyncPacket.class,
            ClientboundRotateHeadPacket.class,
            ClientboundEntityEventPacket.class,
            ClientboundAnimatePacket.class,
            ClientboundHurtAnimationPacket.class,
            ClientboundDamageEventPacket.class,
            ClientboundUpdateAttributesPacket.class,
            ClientboundUpdateMobEffectPacket.class,
            ClientboundRemoveMobEffectPacket.class,
            // Player list, so names and skins resolve on rendered players.
            ClientboundPlayerInfoUpdatePacket.class,
            ClientboundPlayerInfoRemovePacket.class,
            // The target changing dimension: re-keys the viewer's level, without which every
            // chunk that follows belongs to a dimension the viewer is not in.
            ClientboundRespawnPacket.class,
            // Ambient.
            ClientboundSetTimePacket.class,
            // Weather and similar level state. Note the host synthesizes its own
            // LEVEL_CHUNKS_LOAD_START rather than relying on one arriving here; forwarding the
            // target's copy is harmless because the client treats it as idempotent.
            ClientboundGameEventPacket.class,
            // Particles and weather are forwarded only as these packets -- the ones a server
            // actually sends. The much larger set a client spawns for itself is deliberately not
            // captured: the viewer has a real ClientLevel holding the streamed blocks, so its own
            // animateTick generates torch smoke, dripping, spawner flames and the like, and its
            // own weather renderer generates rain and snow from the forwarded weather state. Those
            // particles therefore already appear for a viewer without being sent, and forwarding
            // the target's copies would duplicate them while putting weather -- hundreds of
            // particles per tick during a storm -- onto the wire for no gain.
            ClientboundLevelEventPacket.class,
            ClientboundLevelParticlesPacket.class,
            ClientboundExplodePacket.class,
            ClientboundSoundPacket.class,
            ClientboundSoundEntityPacket.class);

    /** True if this packet kind may be forwarded at all. */
    public static boolean isForwardable(Packet<?> packet) {
        return FORWARDED.contains(packet.getClass());
    }

    /**
     * True if this packet carries terrain.
     *
     * <p>Used to decide when the viewer has something to render, which gates releasing its loading
     * screen: announcing before any terrain has been sent would just move the wait rather than
     * remove it.
     */
    public static boolean isChunkPacket(Packet<?> packet) {
        return packet instanceof ClientboundLevelChunkWithLightPacket;
    }

    /**
     * True if this packet is inside the capture radius, or is not position-scoped.
     *
     * <p>Only whole-chunk packets are clipped. Block and entity packets inside a chunk the viewer
     * never received are harmless -- the viewer's client drops an update for a chunk it does not
     * have -- and testing each one would cost more than it saves.
     *
     * @param local the target's own chunk, or null when there is no player yet (nothing is sent)
     */
    public static boolean isWithinRadius(Packet<?> packet, ChunkPos local) {
        if (local == null) {
            return false;
        }
        int x;
        int z;
        if (packet instanceof ClientboundLevelChunkWithLightPacket p) {
            x = p.getX();
            z = p.getZ();
        } else if (packet instanceof ClientboundForgetLevelChunkPacket p) {
            x = p.pos().x();
            z = p.pos().z();
        } else {
            return true;
        }
        int r = SpectateProtocol.CAPTURE_RADIUS_CHUNKS;
        return Math.abs(x - local.x()) <= r && Math.abs(z - local.z()) <= r;
    }
}
