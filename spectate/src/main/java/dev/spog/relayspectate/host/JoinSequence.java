package dev.spog.relayspectate.host;

import dev.spog.relayspectate.net.SpectateProtocol;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Builds the packets that put a viewer into the target's world.
 *
 * <p>The target's own login packet is <em>not</em> replayed, which was the first design and was
 * wrong in three ways:
 *
 * <ul>
 *   <li><b>Game mode.</b> It carries the target's mode, typically survival. A viewer in survival
 *       has a hunger bar, takes damage and collides with terrain in a world where none of that is
 *       real -- nothing it does is sent anywhere. Spectator is what the feature actually means.</li>
 *   <li><b>View distance.</b> It carries the target server's chunk radius, often 16 or 32, while
 *       the capture sends {@link SpectateProtocol#CAPTURE_RADIUS_CHUNKS}. The client would expect
 *       chunks that never arrive.</li>
 *   <li><b>Loading.</b> Nothing in a replayed login tells the client its chunks have been sent, so
 *       the loading screen never clears. See {@link #levelChunksLoaded()}.</li>
 * </ul>
 *
 * <p>So the login packet is rebuilt from the target's, keeping only what describes the world --
 * dimension, seed, flat/debug flags, sea level -- and replacing what describes the player.
 */
public final class JoinSequence {
    private JoinSequence() {
    }

    /**
     * The viewer's entity id. Deliberately an id the target's server would never assign: the
     * viewer's local player is a fiction that exists only to give the camera something to attach
     * to, and it must not collide with a real entity id arriving in the stream, which would make
     * the client apply the target's entity updates to the viewer's own player.
     */
    public static final int VIEWER_ENTITY_ID = Integer.MAX_VALUE - 1;

    /**
     * Rewrites the target's login packet for a spectating viewer.
     *
     * <p>Keeps the world description and replaces the player description. {@code hardcore} is
     * forced off (it only affects the death screen, which a spectator cannot reach, and a hardcore
     * flag would mislabel the viewer's own HUD) and reduced debug info is forced off so F3 works
     * for whoever is testing this.
     */
    public static ClientboundLoginPacket spectatorLogin(ClientboundLoginPacket target) {
        CommonPlayerSpawnInfo info = target.commonPlayerSpawnInfo();
        CommonPlayerSpawnInfo spectating = new CommonPlayerSpawnInfo(
                info.dimensionType(),
                info.dimension(),
                info.seed(),
                GameType.SPECTATOR,
                GameType.SPECTATOR,
                info.isDebug(),
                info.isFlat(),
                info.lastDeathLocation(),
                info.portalCooldown(),
                info.seaLevel());
        return new ClientboundLoginPacket(
                VIEWER_ENTITY_ID,
                false,
                target.levels(),
                target.maxPlayers(),
                SpectateProtocol.CAPTURE_RADIUS_CHUNKS,
                SpectateProtocol.CAPTURE_RADIUS_CHUNKS,
                false,
                target.showDeathScreen(),
                target.doLimitedCrafting(),
                spectating,
                target.enforcesSecureChat());
    }

    /** Tells the client its view distance, matching what the capture actually sends. */
    public static ClientboundSetChunkCacheRadiusPacket chunkRadius() {
        return new ClientboundSetChunkCacheRadiusPacket(SpectateProtocol.CAPTURE_RADIUS_CHUNKS);
    }

    /**
     * Moves the client's chunk cache to a chunk position.
     *
     * <p>Required, not an optimisation. {@code ClientChunkCache} discards any chunk outside its
     * view centre, and the centre starts at 0,0 -- so every chunk forwarded from a target standing
     * anywhere else is thrown away on arrival. Without this the viewer loads into an empty world
     * no matter how much terrain is streamed.
     */
    public static ClientboundSetChunkCacheCenterPacket chunkCenter(int chunkX, int chunkZ) {
        return new ClientboundSetChunkCacheCenterPacket(chunkX, chunkZ);
    }

    /** Places the viewer's camera at the target's position. */
    public static ClientboundPlayerPositionPacket position(double x, double y, double z,
                                                           float yaw, float pitch) {
        return new ClientboundPlayerPositionPacket(
                0,
                new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, yaw, pitch),
                Set.of());
    }

    /**
     * The event that releases the client's loading screen.
     *
     * <p>{@code ClientPacketListener.handleGameEvent} calls
     * {@code LevelLoadTracker.loadingPacketsReceived()} on this and only this, so a client that
     * never receives it waits on the loading screen forever. A real server sends it once the
     * player's chunks have been dispatched; the host sends it once the first batch has been
     * forwarded.
     */
    public static ClientboundGameEventPacket levelChunksLoaded() {
        return new ClientboundGameEventPacket(
                ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0.0f);
    }
}
