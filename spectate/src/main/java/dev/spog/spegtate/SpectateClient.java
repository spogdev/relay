package dev.spog.spegtate;

import com.mojang.blaze3d.platform.InputConstants;
import dev.spog.spegtate.capture.CaptureHooks;
import dev.spog.spegtate.ui.MenuButton;
import dev.spog.spegtate.ui.SpectateScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint for the Spegtate addon.
 *
 * <p>Separate mod jar rather than part of Relay: spectating is a niche feature with a much larger
 * surface than the rest of the mod (an in-process server, a second socket, a second service), and
 * keeping it out means a Relay user who does not want it does not ship it.
 *
 * <p>Standalone: it keeps its own trust list, service address and keybind-name window, and reads
 * nothing from Relay. The two share a service host and nothing else.
 */
@Environment(EnvType.CLIENT)
public final class SpectateClient implements ClientModInitializer {
    public static final String MOD_ID = "spegtate";

    /**
     * Identifies this build in the log.
     *
     * <p>Bumped whenever behaviour changes. Its only job is to make "the new jar is installed"
     * checkable from the log, which during this feature's development was repeatedly assumed and
     * never verified.
     */
    public static final String BUILD_MARKER = "chathead-1+26.2";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Push the trust set to the service at most this often, in client ticks. */
    private static final int TRUST_INTERVAL_TICKS = 100;

    private static KeyMapping refocusKey;

    private int tickCounter;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        int unbound = InputConstants.UNKNOWN.getValue();
        // Refocus puts a free-flying viewer back on the target, and is the only keybind
        // this addon registers. Unbound by default, matching Relay's convention: binding
        // is a deliberate choice rather than a key taken from the player.
        refocusKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spegtate.refocus", InputConstants.Type.KEYSYM, unbound, category));

        // The menu button, not the keybind, is how a session is started: see MenuButton for why
        // a keybind cannot reach this feature at all.
        MenuButton.register();

        // Admin commands. Registered for every client; Brigadier hides the tree from
        // non-admins and the service re-authorizes each command regardless.
        dev.spog.spegtate.ui.SpegtateCommand.register();

        // The access screen, when the service has refused this account. Driven by a screen
        // event rather than polled in the tick: there is no accessor for the current screen on
        // every branch, and this fires exactly when one opens, which is the moment to replace
        // it. Only at a menu -- never over a world, where it would interrupt what the player
        // was doing for a mod that simply will not run for them.
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register(
                (client, screen, w, h) -> {
                    if (SpectateManager.get().isAccessDenied()
                            && !dev.spog.spegtate.ui.AccessDeniedScreen.alreadyShown()
                            && client.level == null
                            && !(screen instanceof dev.spog.spegtate.ui.AccessDeniedScreen)) {
                        dev.spog.spegtate.ui.AccessDeniedScreen.markShown();
                        client.setScreenAndShow(
                                new dev.spog.spegtate.ui.AccessDeniedScreen(screen));
                    }
                });

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        // The build stamp is the point of this line. Several rounds of "nothing changed" could
        // not be told apart from "the new jar is not the one running", because nothing in the log
        // identified the build -- and the mod version alone does not change between builds during
        // development. BUILD_MARKER is bumped whenever behaviour changes, so one line in the log
        // settles which jar is loaded before anything else is investigated.
        LOGGER.info("Spegtate loaded (build {})", BUILD_MARKER);
    }

    /**
     * The target's display name, falling back to a short id.
     *
     * <p>Read from the viewer's own level, where the target is a normal player entity delivered by
     * the stream, so this is the name the viewer can already see above them.
     */
    private static String targetName(Minecraft client, dev.spog.spegtate.host.ViewerSession s) {
        java.util.UUID id = s.target();
        if (client.level != null) {
            for (var player : client.level.players()) {
                if (id.equals(player.getUUID())) {
                    return player.getName().getString();
                }
            }
        }
        return id == null ? "?" : id.toString().substring(0, 8);
    }

    private void onTick(Minecraft client) {
        SpectateManager manager = SpectateManager.get();
        manager.tick();

        // The inventory key opens the target's inventory instead of the viewer's own, which
        // is empty and meaningless. Consumed here so vanilla does not also open its screen;
        // only while actually watching, so the key behaves normally everywhere else.
        //
        // The open screen is read from client.gui.screen() rather than a field on Minecraft,
        // which has no public one on this version -- this is the same accessor vanilla itself
        // uses where it handles this key.
        var watching = manager.viewing();
        if (watching != null && client.gui.screen() == null) {
            while (client.options.keyInventory.consumeClick()) {
                client.setScreenAndShow(new dev.spog.spegtate.ui.TargetInventoryScreen(
                        null, targetName(client, watching)));
            }
        }

        // Refocus only reaches this feature while a session is live, because that is the
        // only time this addon has a world for the client to dispatch keys in. Starting
        // and stopping a session are the menu's job; see MenuButton.
        while (refocusKey.consumeClick()) {
            var active = manager.viewing();
            if (active != null) {
                // Reported on the action bar rather than silently, because a refocus that cannot
                // happen yet -- no position from the target so far -- is otherwise
                // indistinguishable from the key not being bound.
                if (active.refocus()) {
                    // A viewer that flew far from the target has had its own cache drop the
                    // terrain it left behind, so refocusing can land it somewhere empty. Ask for
                    // the whole surroundings again rather than leaving the repair loop to find it
                    // a handful of chunks at a time.
                    boolean empty = active.targetChunkMissing();
                    if (empty) {
                        SpectateManager.get().requestResweep();
                    }
                    client.gui.hud.setOverlayMessage(
                            SpectateLang.text(empty ? "status.refocused_loading"
                                    : "status.refocused", targetName(client, active)),
                            false);
                } else {
                    client.gui.hud.setOverlayMessage(
                            SpectateLang.text("status.refocus_waiting"), false);
                }
            }
        }

        // The socket is wanted whenever this client could be either role: in-game it may be a
        // target, and in the menu it may be a viewer. So it is kept up in both cases, but only
        // once a service address is configured.
        //
        // Consent is re-sent on this interval rather than only on change, so the service's view
        // cannot drift from the config's: a dropped frame or a reconnect would otherwise leave it
        // enforcing a stale answer, which for a flag that withholds access is the wrong way to
        // fail. It is also what re-establishes consent after a reconnect, since the service
        // starts every session assuming none.
        if (++tickCounter >= TRUST_INTERVAL_TICKS) {
            tickCounter = 0;
            String url = SpectateConfig.get().serviceWebSocketUrl();
            if (url != null) {
                manager.connect(url);
                manager.sendConsent(SpectateConfig.get().allowSpectators);
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
