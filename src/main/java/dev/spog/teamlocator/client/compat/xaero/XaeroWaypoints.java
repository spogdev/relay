package dev.spog.teamlocator.client.compat.xaero;

import dev.spog.teamlocator.TeamLocatorConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

/**
 * Adds a shared waypoint to Xaero's Minimap.
 *
 * <p><b>Only classloaded once {@link XaeroCompat#isMinimapInstalled()} is true.</b> Every Xaero type
 * is touched from inside this class, so a machine without the mod never resolves them — the same
 * arrangement the trackers use, and why the caller must check first rather than catching
 * NoClassDefFoundError after the fact.
 */
@Environment(EnvType.CLIENT)
public final class XaeroWaypoints {
    /** Initials Xaero shows on the marker; it uses the first letters of the name by convention. */
    private static final int MAX_SYMBOL_LENGTH = 2;

    private XaeroWaypoints() {
    }

    /**
     * Add a waypoint to the player's current set.
     *
     * @return true if it was added; false when the minimap has no active world yet (at the title
     *         screen, or before it has resolved which world you are in)
     */
    public static boolean add(String name, int x, int y, int z) {
        try {
            MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
            if (session == null) {
                return false;
            }
            MinimapWorld world = session.getWorldManager().getCurrentWorld();
            if (world == null) {
                return false;
            }
            WaypointSet set = world.getCurrentWaypointSet();
            if (set == null) {
                return false;
            }
            String symbol = name.isEmpty()
                    ? "?"
                    : name.substring(0, Math.min(MAX_SYMBOL_LENGTH, name.length()));
            set.add(new Waypoint(x, y, z, name, symbol,
                    WaypointColor.AQUA, WaypointPurpose.NORMAL));
            return true;
        } catch (RuntimeException | LinkageError e) {
            // Xaero's internals are not a published API, so a version bump can move any of this.
            // A failed waypoint add must not take the chat handler down with it.
            TeamLocatorConstants.LOGGER.warn("Could not add waypoint to Xaero: {}", e.toString());
            return false;
        }
    }
}
