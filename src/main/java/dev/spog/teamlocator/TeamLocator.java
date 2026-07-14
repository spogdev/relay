package dev.spog.teamlocator;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TeamLocator implements ModInitializer {
    public static final String MOD_ID = "teamlocator";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("TeamLocator initializing");
    }
}
