package dev.spog.relayspectate.host;

import dev.spog.relayspectate.net.SpectateProtocol;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkRenderDistanceCenterS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkLoadDistanceS2CPacket;
import net.minecraft.network.packet.s2c.play.CommonPlayerSpawnInfo;
import net.minecraft.entity.EntityPosition;
import com.mojang.authlib.GameProfile;
import dev.spog.relayspectate.capture.TabList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.world.GameMode;

import java.util.EnumSet;
import java.util.List;
import net.minecraft.util.math.Vec3d;

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
    public static GameJoinS2CPacket spectatorLogin(GameJoinS2CPacket target) {
        CommonPlayerSpawnInfo info = target.commonPlayerSpawnInfo();
        CommonPlayerSpawnInfo spectating = new CommonPlayerSpawnInfo(
                info.dimensionType(),
                info.dimension(),
                info.seed(),
                GameMode.SPECTATOR,
                GameMode.SPECTATOR,
                info.isDebug(),
                info.isFlat(),
                info.lastDeathLocation(),
                info.portalCooldown(),
                info.seaLevel());
        return new GameJoinS2CPacket(
                VIEWER_ENTITY_ID,
                false,
                target.dimensionIds(),
                target.maxPlayers(),
                SpectateProtocol.VIEWER_RADIUS_CHUNKS,
                SpectateProtocol.VIEWER_RADIUS_CHUNKS,
                false,
                target.showDeathScreen(),
                target.doLimitedCrafting(),
                spectating,
                target.enforcesSecureChat());
    }

    /**
     * A tab-list entry for the viewer itself, in spectator.
     *
     * <p>Required for spectator mode to work at all, which is not obvious. The login packet's game
     * mode is not what a client reads back: {@code AbstractClientPlayerEntity.gameMode()} is
     * {@code getPlayerInfo().getGameMode()}, a lookup of the player's <em>own tab-list entry</em>,
     * and it returns null when there is none. {@code PlayerEntity.tick} then sets
     * {@code noPhysics = isSpectator()} every tick, so with no entry the viewer collides with
     * everything and cannot fly through blocks -- however plainly the login packet said
     * SPECTATOR.
     *
     * <p>The forwarded list cannot supply this. It is the target's, so it contains the viewer only
     * if the viewer happens to be on the target's server too, and then with the viewer's real game
     * mode rather than spectator.
     *
     * <p>Built with no profile properties: this entry exists to carry a game mode, and the
     * viewer's own skin is resolved by its client from its own account.
     *
     * <p>Takes the registries explicitly rather than reading them from {@code mc.getNetworkHandler()}.
     * This runs during loopback login, when that connection is the viewer's own and may be absent
     * or mid-handshake, and depending on it made this return null every time.
     */
    public static PlayerListS2CPacket selfSpectating(
            MinecraftClient mc, net.minecraft.registry.DynamicRegistryManager registries) {
        GameProfile profile = new GameProfile(
                mc.getSession().getUuidOrNull(), mc.getSession().getUsername());
        return TabList.rebuild(
                EnumSet.of(
                        PlayerListS2CPacket.Action.ADD_PLAYER,
                        PlayerListS2CPacket.Action.UPDATE_LISTED,
                        PlayerListS2CPacket.Action.UPDATE_GAME_MODE),
                List.of(new PlayerListS2CPacket.Entry(
                        profile.id(),
                        profile,
                        false,
                        0,
                        GameMode.SPECTATOR,
                        null,
                        true,
                        0,
                        null)),
                registries);
    }

    /** Tells the client its view distance, matching what the capture actually sends. */
    public static ChunkLoadDistanceS2CPacket chunkRadius() {
        return new ChunkLoadDistanceS2CPacket(SpectateProtocol.VIEWER_RADIUS_CHUNKS);
    }

    /**
     * Moves the client's chunk cache to a chunk position.
     *
     * <p>Required, not an optimisation. {@code ClientChunkCache} discards any chunk outside its
     * view centre, and the centre starts at 0,0 -- so every chunk forwarded from a target standing
     * anywhere else is thrown away on arrival. Without this the viewer loads into an empty world
     * no matter how much terrain is streamed.
     */
    public static ChunkRenderDistanceCenterS2CPacket chunkCenter(int chunkX, int chunkZ) {
        return new ChunkRenderDistanceCenterS2CPacket(chunkX, chunkZ);
    }

    /** Places the viewer's camera at the target's position. */
    public static PlayerPositionLookS2CPacket position(double x, double y, double z,
                                                           float yaw, float pitch) {
        return new PlayerPositionLookS2CPacket(
                0,
                new EntityPosition(new Vec3d(x, y, z), Vec3d.ZERO, yaw, pitch),
                Set.of());
    }

    /**
     * The event that releases the client's loading screen.
     *
     * <p>{@code ClientPlayNetworkHandler.handleGameEvent} calls
     * {@code LevelLoadTracker.loadingPacketsReceived()} on this and only this, so a client that
     * never receives it waits on the loading screen forever. A real server sends it once the
     * player's chunks have been dispatched; the host sends it once the first batch has been
     * forwarded.
     */
    public static GameStateChangeS2CPacket levelChunksLoaded() {
        return new GameStateChangeS2CPacket(
                GameStateChangeS2CPacket.INITIAL_CHUNKS_COMING, 0.0f);
    }
}
