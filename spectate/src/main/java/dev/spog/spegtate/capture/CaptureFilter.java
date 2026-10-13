package dev.spog.spegtate.capture;

import dev.spog.spegtate.net.SpectateProtocol;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockEventS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkSentS2CPacket;
import net.minecraft.network.packet.s2c.play.StartChunkSendS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkBiomeDataS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionSyncS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.DamageTiltS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldEventS2CPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityS2CPacket;
import net.minecraft.network.packet.s2c.play.MoveMinecartAlongTrackS2CPacket;
import net.minecraft.network.packet.s2c.play.VehicleMoveS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.RemoveEntityStatusEffectS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAttachS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPassengersSetS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAttributesS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusEffectS2CPacket;
import net.minecraft.util.math.ChunkPos;

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
 *   <li>{@code GameJoinS2CPacket} and the configuration-phase packets -- those are replayed
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
            ChunkDataS2CPacket.class,
            UnloadChunkS2CPacket.class,
            LightUpdateS2CPacket.class,
            ChunkBiomeDataS2CPacket.class,
            StartChunkSendS2CPacket.class,
            ChunkSentS2CPacket.class,
            BlockUpdateS2CPacket.class,
            BlockEntityUpdateS2CPacket.class,
            BlockEventS2CPacket.class,
            BlockBreakingProgressS2CPacket.class,
            // Entities.
            EntitySpawnS2CPacket.class,
            EntitiesDestroyS2CPacket.class,
            EntityTrackerUpdateS2CPacket.class,
            EntityVelocityUpdateS2CPacket.class,
            EntityAttachS2CPacket.class,
            EntityEquipmentUpdateS2CPacket.class,
            EntityPassengersSetS2CPacket.class,
            EntityS2CPacket.MoveRelative.class,
            EntityS2CPacket.RotateAndMoveRelative.class,
            EntityS2CPacket.Rotate.class,
            MoveMinecartAlongTrackS2CPacket.class,
            VehicleMoveS2CPacket.class,
            EntityPositionS2CPacket.class,
            EntityPositionSyncS2CPacket.class,
            EntitySetHeadYawS2CPacket.class,
            EntityStatusS2CPacket.class,
            EntityAnimationS2CPacket.class,
            DamageTiltS2CPacket.class,
            EntityDamageS2CPacket.class,
            EntityAttributesS2CPacket.class,
            EntityStatusEffectS2CPacket.class,
            RemoveEntityStatusEffectS2CPacket.class,
            // PlayerEntity list, so names and skins resolve on rendered players.
            PlayerListS2CPacket.class,
            PlayerRemoveS2CPacket.class,
            // The target changing dimension: re-keys the viewer's level, without which every
            // chunk that follows belongs to a dimension the viewer is not in.
            PlayerRespawnS2CPacket.class,
            // Ambient.
            WorldTimeUpdateS2CPacket.class,
            // Weather and similar level state. Note the host synthesizes its own
            // LEVEL_CHUNKS_LOAD_START rather than relying on one arriving here; forwarding the
            // target's copy is harmless because the client treats it as idempotent.
            GameStateChangeS2CPacket.class,
            // Particles and weather are forwarded only as these packets -- the ones a server
            // actually sends. The much larger set a client spawns for itself is deliberately not
            // captured: the viewer has a real ClientWorld holding the streamed blocks, so its own
            // animateTick generates torch smoke, dripping, spawner flames and the like, and its
            // own weather renderer generates rain and snow from the forwarded weather state. Those
            // particles therefore already appear for a viewer without being sent, and forwarding
            // the target's copies would duplicate them while putting weather -- hundreds of
            // particles per tick during a storm -- onto the wire for no gain.
            WorldEventS2CPacket.class,
            ParticleS2CPacket.class,
            ExplosionS2CPacket.class,
            PlaySoundS2CPacket.class,
            PlaySoundFromEntityS2CPacket.class);

    /** True if this packet kind may be forwarded at all. */
    public static boolean isForwardable(Packet<?> packet) {
        if (!FORWARDED.contains(packet.getClass())) {
            return false;
        }
        // One event on an otherwise forwardable packet has to be dropped. CHANGE_GAME_MODE is
        // carried by GameStateChangeS2CPacket, the same packet as the weather events and
        // LEVEL_CHUNKS_LOAD_START, so the class cannot simply be removed from the list above.
        // Forwarding it made the target changing game mode change the viewer's: the client
        // applies it to its own local player through setLocalMode, which took the viewer out of
        // spectator -- restoring collision, so it could no longer fly through blocks, and
        // mislabelling its HUD. The viewer's game mode is set once, by the login packet, and
        // nothing in the target's stream has any business altering it.
        if (packet instanceof GameStateChangeS2CPacket p) {
            return p.getReason() != GameStateChangeS2CPacket.GAME_MODE_CHANGED;
        }
        // Movement for a player this client reports itself is dropped: the report is absolute
        // and arrives every tick, while MoveRelative applies a delta to the tracked position
        // that the report has already overwritten. Forwarding both made those players snap
        // backwards and forwards, and only those players -- anyone outside the reported set had
        // one source and behaved normally.
        Integer moved = movedEntity(packet);
        return moved == null || !dev.spog.spegtate.SpectateRoster.reportsPeer(moved);
    }

    /**
     * True if this packet carries terrain.
     *
     * <p>Used to decide when the viewer has something to render, which gates releasing its loading
     * screen: announcing before any terrain has been sent would just move the wait rather than
     * remove it.
     */
    public static boolean isChunkPacket(Packet<?> packet) {
        return packet instanceof ChunkDataS2CPacket;
    }

    /**
     * True if this packet is inside the captured region, or is not position-scoped.
     *
     * <p>Only whole-chunk packets are clipped. Block and entity packets inside a chunk the viewer
     * never received are harmless -- the viewer's client drops an update for a chunk it does not
     * have -- and testing each one would cost more than it saves.
     *
     * <p>A square centred on the target, with no bias towards where it is going. Two earlier
     * shapes were tried and both carved holes in the viewer's world:
     *
     * <ul>
     *   <li>Two boxes, one on the target and one projected a full radius ahead. Boxes of
     *       half-width r whose centres are 2r apart meet only along the travel axis, so travelling
     *       diagonally they separated into two squares joined at a corner and everything in the
     *       notch between them was dropped -- a clean-edged trench through the world.</li>
     *   <li>One box whose centre was nudged towards the target's travel. Moving a square forwards
     *       gains ground ahead and loses exactly as much to the sides and behind, so the viewer
     *       was missing wedges either side of the direction the target had walked -- the same
     *       amount of terrain as before, in a less obvious shape.</li>
     * </ul>
     *
     * <p>Both were attempts to solve a problem that does not belong here. Terrain ahead of a
     * walking target was being lost because a server dispatches chunks at the player's full view
     * distance, so they arrive while the target is still far from them and fail a test centred on
     * where it stands. The fix for that is to send those chunks when the target reaches them --
     * which {@link dev.spog.spegtate.capture.ChunkSweep} can do from the target's own cache,
     * and which the viewer asks for when it finds a hole -- not to deform the region and trade one
     * set of missing chunks for another. The region this clips to should simply match what the
     * target's own client holds around it, and a centred square is that shape.
     *
     * @param local the target's own chunk, or null when there is no player yet (nothing is sent)
     */
    public static boolean isWithinRadius(Packet<?> packet, ChunkPos local) {
        if (local == null) {
            return false;
        }
        int x;
        int z;
        if (packet instanceof ChunkDataS2CPacket p) {
            x = p.getChunkX();
            z = p.getChunkZ();
        } else if (packet instanceof UnloadChunkS2CPacket p) {
            x = p.pos().x;
            z = p.pos().z;
        } else {
            return true;
        }
        int r = SpectateProtocol.CAPTURE_RADIUS_CHUNKS;
        return Math.abs(x - local.x) <= r && Math.abs(z - local.z) <= r;
    }

    /**
     * The entity a movement packet moves, or null if it is not a movement packet.
     *
     * <p>Only the kinds that carry a position; rotation-only packets are left alone, since the
     * position reports do not supersede them.
     */
    private static Integer movedEntity(Packet<?> packet) {
        if (packet instanceof EntityS2CPacket p) {
            // No id getter on this one, and a rotation-only move carries no position to
            // conflict with, so it is left alone.
            if (!p.isPositionChanged()) {
                return null;
            }
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world == null) {
                return null;
            }
            var moved = p.getEntity(mc.world);
            return moved == null ? null : moved.getId();
        }
        if (packet instanceof EntityPositionS2CPacket p) {
            return p.entityId();
        }
        if (packet instanceof EntityPositionSyncS2CPacket p) {
            return p.id();
        }
        return null;
    }
}
