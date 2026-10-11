package dev.spog.relayspectate;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint for the Relay Spectate addon.
 *
 * <p>Separate mod jar rather than part of Relay: spectating is a niche feature with a much larger
 * surface than the rest of the mod (an in-process server, a second socket), and keeping it out
 * means a Relay user who does not want it does not ship it. The addon reads Relay's trust list and
 * relay address; Relay itself is unaware of the addon.
 */
@Environment(EnvType.CLIENT)
public final class SpectateClient implements ClientModInitializer {
    public static final String MOD_ID = "relayspectate";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("Relay Spectate loaded");
    }
}
