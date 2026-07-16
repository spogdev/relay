package dev.spog.teamlocator.client.compat.xaero;

import dev.spog.teamlocator.TeamLocatorConstants;
import dev.spog.teamlocator.client.ClientState;
import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.TrackedPos;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import xaero.map.WorldMap;
import xaero.map.radar.tracker.system.IPlayerTrackerSystem;
import xaero.map.radar.tracker.system.ITrackedPlayerReader;
import xaero.map.radar.tracker.system.PlayerTrackerSystemManager;

import java.util.Collections;
import java.util.Iterator;
import java.util.UUID;

/**
 * Feeds the relay's teammate positions to Xaero's World Map through its player tracker system, so
 * teammates appear on the fullscreen map at any distance (with the map's usual cross-dimension
 * coordinate handling).
 *
 * <p>Only classloaded from {@link XaeroCompat} after an {@code isModLoaded("xaeroworldmap")} check —
 * never import this class from anywhere else.
 */
@Environment(EnvType.CLIENT)
public final class XaeroWorldMapTracker implements IPlayerTrackerSystem<TrackedPos> {
    private final ITrackedPlayerReader<TrackedPos> reader = new Reader();

    private XaeroWorldMapTracker() {
    }

    /**
     * Registers with the world map's tracker manager. The manager is created in
     * {@code WorldMap}'s static initializer, so it exists as soon as the class is touched, but keep
     * the null check for symmetry/safety — {@link XaeroCompat} retries next tick on false.
     */
    static boolean tryRegister() {
        PlayerTrackerSystemManager manager = WorldMap.playerTrackerSystemManager;
        if (manager == null) {
            return false;
        }
        manager.register(TeamLocatorConstants.MOD_ID, new XaeroWorldMapTracker());
        return true;
    }

    @Override
    public ITrackedPlayerReader<TrackedPos> getReader() {
        return reader;
    }

    /**
     * A live view of the relay store — same entries and expiry semantics as the HUD. Deliberately
     * unfiltered: the world map's own tracker renderer ({@code PlayerTrackerMapElementRenderer})
     * looks the live entity up per render and switches on its presence itself, so it needs no
     * outside dedup. Config is checked per call, so the map-icons toggle applies instantly.
     */
    @Override
    public Iterator<TrackedPos> getTrackedPlayerIterator() {
        if (!TeamLocatorClient.CONFIG.xaeroMapIcons) {
            return Collections.emptyIterator();
        }
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
        public RegistryKey<World> getDimension(TrackedPos pos) {
            return RegistryKey.of(RegistryKeys.WORLD, pos.dimension());
        }
    }
}
