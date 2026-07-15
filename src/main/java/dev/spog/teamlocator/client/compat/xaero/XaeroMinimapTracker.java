package dev.spog.teamlocator.client.compat.xaero;

import dev.spog.teamlocator.TeamLocatorConstants;
import dev.spog.teamlocator.client.ClientState;
import dev.spog.teamlocator.client.TrackedPos;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.common.HudMod;
import xaero.hud.minimap.player.tracker.system.IRenderedPlayerTracker;
import xaero.hud.minimap.player.tracker.system.ITrackedPlayerReader;
import xaero.hud.minimap.player.tracker.system.RenderedPlayerTrackerManager;

import java.util.Iterator;
import java.util.UUID;

/**
 * Feeds the relay's teammate positions to Xaero's Minimap through its player tracker system, so
 * teammates show up on the minimap (and its radar arrows) no matter how far away they are.
 *
 * <p>Only classloaded from {@link XaeroCompat} after an {@code isModLoaded("xaerominimap")} check —
 * never import this class from anywhere else.
 */
@Environment(EnvType.CLIENT)
final class XaeroMinimapTracker implements IRenderedPlayerTracker<TrackedPos> {
    private final ITrackedPlayerReader<TrackedPos> reader = new Reader();

    private XaeroMinimapTracker() {
    }

    /**
     * Registers with the minimap's tracker manager. Returns false (try again next tick) while the
     * minimap has not finished constructing itself.
     */
    static boolean tryRegister() {
        HudMod hudMod = HudMod.INSTANCE;
        if (hudMod == null) {
            return false;
        }
        RenderedPlayerTrackerManager manager = hudMod.getRenderedPlayerTrackerManager();
        if (manager == null) {
            return false;
        }
        manager.register(TeamLocatorConstants.MOD_ID, new XaeroMinimapTracker());
        return true;
    }

    @Override
    public ITrackedPlayerReader<TrackedPos> getReader() {
        return reader;
    }

    /** A live view of the relay store — same entries and expiry semantics as the HUD. */
    @Override
    public Iterator<TrackedPos> getTrackedPlayerIterator() {
        return ClientState.latest().iterator();
    }

    private static final class Reader implements ITrackedPlayerReader<TrackedPos> {
        @Override
        public UUID getId(TrackedPos pos) {
            return pos.id();
        }

        @Override
        public double getX(TrackedPos pos) {
            return pos.x();
        }

        @Override
        public double getY(TrackedPos pos) {
            return pos.y();
        }

        @Override
        public double getZ(TrackedPos pos) {
            return pos.z();
        }

        @Override
        public ResourceKey<Level> getDimension(TrackedPos pos) {
            return ResourceKey.create(Registries.DIMENSION, pos.dimension());
        }
    }
}
