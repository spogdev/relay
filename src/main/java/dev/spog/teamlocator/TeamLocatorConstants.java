package dev.spog.teamlocator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod id and logger. The mod is client-only (there is no server component any more — routing lives
 * in the standalone relay service under {@code relay/}), so there is no ModInitializer here.
 */
public final class TeamLocatorConstants {
    public static final String MOD_ID = "teamlocator";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private TeamLocatorConstants() {
    }
}
