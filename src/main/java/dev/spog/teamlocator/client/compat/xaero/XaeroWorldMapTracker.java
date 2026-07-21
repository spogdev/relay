package dev.spog.teamlocator.client.compat.xaero;

import dev.spog.teamlocator.TeamLocatorConstants;
import dev.spog.teamlocator.client.ClientState;
import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.TrackedPos;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.WorldMap;
import xaero.map.radar.tracker.system.IPlayerTrackerSystem;
import xaero.map.radar.tracker.system.ITrackedPlayerReader;
import xaero.map.radar.tracker.system.PlayerTrackerSystemManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
     * One stable {@link TrackedPos} instance per teammate, replaced only on dimension change.
     * Xaero's collector reuses a map element only while {@code element.getPlayer()} is the
     * <em>same instance</em> (an {@code if_acmpeq} in {@code updateForSystem}); handing it each
     * fresh 5 Hz snapshot rebuilt the element — and reset its hover-zoom {@code fadeAnim} — five
     * times a second, which is exactly the "hovered icon zooms in and out while they move" bug.
     * The coords inside the cached instance go stale, but the world-map reader never reads them:
     * it reports {@link ClientState#interpolated(UUID)} by id. The dimension it does read, hence
     * the replace-on-dimension-change (a rebuild is correct there — the icon changes maps).
     */
    private final Map<UUID, TrackedPos> stableHandles = new ConcurrentHashMap<>();

    /**
     * A live view of the relay store — same entries and expiry semantics as the HUD, mapped
     * through {@link #stableHandles} so element identity survives position updates. Deliberately
     * unfiltered: the world map's own tracker renderer ({@code PlayerTrackerMapElementRenderer})
     * looks the live entity up per render and switches on its presence itself, so it needs no
     * outside dedup. Config is checked per call, so the map-icons toggle applies instantly.
     */
    @Override
    public Iterator<TrackedPos> getTrackedPlayerIterator() {
        if (!TeamLocatorClient.CONFIG.xaeroMapIcons) {
            return Collections.emptyIterator();
        }
        List<TrackedPos> live = ClientState.latest();
        Set<UUID> seen = new HashSet<>();
        List<TrackedPos> out = new ArrayList<>(live.size());
        for (TrackedPos pos : live) {
            seen.add(pos.id());
            TrackedPos handle = stableHandles.compute(pos.id(), (id, existing) ->
                    existing != null && existing.dimension().equals(pos.dimension()) ? existing : pos);
            out.add(handle);
        }
        // Drop handles for teammates no longer reported, so a returning player gets a fresh
        // element instead of a resurrected one, and the map can't leak entries.
        stableHandles.keySet().retainAll(seen);
        return out.iterator();
    }

    /**
     * Reports an <em>interpolated</em> position (see {@link ClientState#interpolated(UUID)}) rather
     * than the raw snapshot. Positions arrive at ~5 Hz, so a raw icon teleports 200 ms at a time;
     * on the fullscreen map that made a hovered icon's zoom oscillate as the hover hit-test flipped
     * on each jump. Interpolating makes the icon glide, so the hit-test stays stable and the zoom
     * holds. The minimap reader deliberately stays raw — its icon handling is being reworked
     * separately, and this fix is scoped to the world map.
     */
    private static final class Reader implements ITrackedPlayerReader<TrackedPos> {
        @Override
        public UUID getId(TrackedPos pos) {
            return pos.id();
        }

        @Override
        public double getX(TrackedPos pos) {
            double[] p = ClientState.interpolated(pos.id());
            return p != null ? p[0] : pos.x();
        }

        @Override
        public double getY(TrackedPos pos) {
            double[] p = ClientState.interpolated(pos.id());
            return p != null ? p[1] : pos.y();
        }

        @Override
        public double getZ(TrackedPos pos) {
            double[] p = ClientState.interpolated(pos.id());
            return p != null ? p[2] : pos.z();
        }

        @Override
        public ResourceKey<Level> getDimension(TrackedPos pos) {
            return ResourceKey.create(Registries.DIMENSION, pos.dimension());
        }
    }
}
