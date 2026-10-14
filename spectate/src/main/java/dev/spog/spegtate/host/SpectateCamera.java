package dev.spog.spegtate.host;

import dev.spog.spegtate.SpectateClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * Attaches the viewer's camera to the target, so spectating shows what the target sees rather than
 * a free camera in their world.
 *
 * <p>This is what makes the feature defensible as well as useful. A free camera in a streamed world
 * can be moved to look through walls and around corners the target cannot see -- the client holds
 * the chunk data regardless of line of sight -- which is the behaviour a server operator would
 * reasonably call an exploit. Locked to the target, the viewer sees what the target's own client
 * was told and nothing more.
 *
 * <p>Implemented with {@code Minecraft.setCameraEntity}, which is how vanilla spectator mode
 * attaches to another entity, rather than by moving the viewer's own player each frame. The
 * difference matters for rendering: a camera entity gets the target's eye height, pose and view
 * bobbing, where a teleported player would be a camera at the target's feet.
 *
 * <p>The target's entity arrives through the forwarded stream like any other player, so it does not
 * exist immediately. {@link #tick()} keeps looking until it appears and re-attaches if the entity
 * is replaced, which happens when the target respawns or changes dimension.
 */
public final class SpectateCamera {
    /**
     * The player the camera is on.
     *
     * <p>Not final: the spectator bar can move the focus to another player, the same way vanilla
     * spectator mode teleports between players. The capture target is only where a session
     * starts, not where it is stuck.
     */
    private volatile UUID target;

    /** Logged once, so a target out of range does not spam while the viewer waits. */
    private boolean warnedMissing;
    private boolean attached;

    public SpectateCamera(UUID target) {
        this.target = target;
    }

    /**
     * Attaches or re-attaches the camera if needed. Called once per client tick while a session is
     * live, from the client thread.
     */
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (dev.spog.spegtate.SpectateConfig.get().freeCamera) {
            // Free movement: the viewer flies its own spectator player and the camera stays on it.
            // Release once rather than every tick, so a viewer who was attached before the setting
            // changed is handed back control exactly once.
            if (attached) {
                mc.setCameraEntity(mc.player);
                attached = false;
                SpectateClient.LOGGER.debug("spectate: free camera; released from target");
            }
            return;
        }
        Entity current = mc.getCameraEntity();
        AbstractClientPlayer entity = findTarget(mc);
        if (entity == null) {
            // Fall back to the viewer's own player rather than leaving the camera on a stale
            // entity: a removed entity would freeze the view at wherever it was last seen.
            if (attached) {
                mc.setCameraEntity(mc.player);
                attached = false;
            }
            if (!warnedMissing) {
                warnedMissing = true;
                SpectateClient.LOGGER.debug("spectate: target entity not present yet");
            }
            return;
        }
        warnedMissing = false;
        if (current != entity) {
            mc.setCameraEntity(entity);
            attached = true;
            SpectateClient.LOGGER.debug("spectate: camera attached to target");
        }
    }

    /**
     * Moves the camera to another player.
     *
     * <p>The chunk centre follows {@code getCameraEntity()} on the next tick, so the streamed
     * terrain re-centres on the new focus without anything else being told.
     *
     * <p>Only the focus changes. The capture is still centred on the session's own target, so
     * focusing someone near the edge of the streamed region shows them standing at the edge of
     * the terrain that exists -- which is why the bar offers only players inside it.
     */
    public void focusOn(UUID player) {
        target = player;
        warnedMissing = false;
        // Taken on the next tick rather than here: tick() already resolves the entity, handles it
        // not existing yet, and is the one place the camera is assigned.
    }

    /** The player the camera is currently on. */
    public UUID focus() {
        return target;
    }

    /** Releases the camera back to the viewer's own player. */
    public void release() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
        }
        attached = false;
    }

    private AbstractClientPlayer findTarget(Minecraft mc) {
        for (AbstractClientPlayer player : mc.level.players()) {
            if (target.equals(player.getUUID())) {
                return player;
            }
        }
        return null;
    }
}
