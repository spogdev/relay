package dev.spog.teamlocator.client.compat.xaero;

import dev.spog.teamlocator.TeamLocatorConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Optional Xaero's Minimap / World Map integration: teammates tracked through the relay appear on
 * both maps at any distance via Xaero's player tracker system API (normal radar only covers nearby
 * entities).
 *
 * <p>This guard class deliberately contains no {@code xaero.*} imports. The classes that do
 * ({@link XaeroMinimapTracker}, {@link XaeroWorldMapTracker}) are only ever classloaded from inside
 * the mod-loaded checks below, so a missing Xaero mod can never cause a {@code NoClassDefFoundError}
 * — the jars are compileOnly dependencies.
 *
 * <p>Registration is deferred to the client tick loop rather than done in our entrypoint because
 * Fabric gives no ordering guarantee between our client entrypoint and Xaero's: the minimap's
 * {@code HudMod.INSTANCE} only exists once its own entrypoint has run. By the first tick every
 * entrypoint has run; the pending flags make the steady-state cost of the handler two boolean
 * loads.
 */
@Environment(EnvType.CLIENT)
public final class XaeroCompat {
    private static boolean minimapPending;
    private static boolean worldMapPending;

    private XaeroCompat() {
    }

    /** Call once from the client entrypoint. No-op when neither Xaero mod is installed. */
    public static void init() {
        FabricLoader loader = FabricLoader.getInstance();
        minimapPending = loader.isModLoaded("xaerominimap");
        worldMapPending = loader.isModLoaded("xaeroworldmap");
        if (!minimapPending && !worldMapPending) {
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void tick() {
        if (minimapPending && XaeroMinimapTracker.tryRegister()) {
            minimapPending = false;
            TeamLocatorConstants.LOGGER.info("Registered teammate tracker with Xaero's Minimap");
        }
        if (worldMapPending && XaeroWorldMapTracker.tryRegister()) {
            worldMapPending = false;
            TeamLocatorConstants.LOGGER.info("Registered teammate tracker with Xaero's World Map");
        }
    }
}
