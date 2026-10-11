package dev.spog.relayspectate;

import com.mojang.blaze3d.platform.InputConstants;
import dev.spog.relayspectate.capture.CaptureHooks;
import dev.spog.relayspectate.ui.MenuButton;
import dev.spog.relayspectate.ui.SpectateScreen;
import dev.spog.relayspectate.ui.WatchedIndicator;
import dev.spog.teamlocator.client.TeamLocatorClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint for the Relay Spectate addon.
 *
 * <p>Separate mod jar rather than part of Relay: spectating is a niche feature with a much larger
 * surface than the rest of the mod (an in-process server, a second socket, a second service), and
 * keeping it out means a Relay user who does not want it does not ship it. The addon reads Relay's
 * trust list and config; Relay itself is unaware of the addon.
 */
@Environment(EnvType.CLIENT)
public final class SpectateClient implements ClientModInitializer {
    public static final String MOD_ID = "relayspectate";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Push the trust set to the service at most this often, in client ticks. */
    private static final int TRUST_INTERVAL_TICKS = 100;

    private static KeyMapping openMenuKey;
    private static KeyMapping stopKey;

    private int tickCounter;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        int unbound = InputConstants.UNKNOWN.getValue();
        // Unbound by default, matching Relay's convention: binding is a deliberate choice.
        openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.relayspectate.open_menu", InputConstants.Type.KEYSYM, unbound, category));
        stopKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.relayspectate.stop", InputConstants.Type.KEYSYM, unbound, category));

        HudElementRegistry.addLast(WatchedIndicator.ID, new WatchedIndicator());
        // The menu button, not the keybind, is how a session is started: see MenuButton for why
        // a keybind cannot reach this feature at all.
        MenuButton.register();

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        LOGGER.info("Relay Spectate loaded");
    }

    private void onTick(Minecraft client) {
        SpectateManager manager = SpectateManager.get();
        manager.tick();

        // Both keybinds only reach this feature while a session is live, because that is the only
        // time this addon has a world for Minecraft to dispatch keys in. Starting a session is the
        // menu button's job; see MenuButton.
        while (openMenuKey.consumeClick()) {
            if (manager.viewing() != null) {
                client.setScreen(new SpectateScreen(client.screen));
            }
        }
        while (stopKey.consumeClick()) {
            manager.stopViewing();
        }

        // The socket is wanted whenever this client could be either role: in-game it may be a
        // target, and in the menu it may be a viewer. So it is kept up in both cases, but only
        // once Relay itself is configured -- the addon has no service address of its own to fall
        // back on.
        if (++tickCounter >= TRUST_INTERVAL_TICKS) {
            tickCounter = 0;
            String url = SpectateConfig.get().serviceWebSocketUrl();
            if (url != null) {
                manager.connect(url);
                manager.sendTrust(TeamLocatorClient.CONFIG.effectiveSharingSet());
            }
        }

        // A disconnect from this client's own server invalidates the recorded handshake: its
        // registries belong to that server, and a session started from them after reconnecting
        // elsewhere would decode against the wrong tables.
        //
        // "No connection" is not the test on its own: while this client is viewing a session its
        // connection is the loopback, which is non-null and nothing to do with the server the
        // recording came from. A client that is viewing cannot also be on a real server, so the
        // two conditions together identify a genuine disconnect.
        boolean onRealServer = client.getConnection() != null && manager.viewing() == null;
        if (!onRealServer && CaptureHooks.handshake().isComplete()) {
            CaptureHooks.onDisconnect();
        }
    }
}
