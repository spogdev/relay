package dev.spog.teamlocator.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.spog.teamlocator.client.config.TeamConfig;
import dev.spog.teamlocator.client.hud.TeamHud;
import dev.spog.teamlocator.client.net.RelayClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Client entrypoint. The mod is client-only: coordinates and pings travel through the user's relay
 * service (see {@code relay/}), never through the Minecraft server, so it works on any server. This
 * class loads config, registers the keybind and HUD, connects the relay when joining a server, and
 * pushes our own position / trust state to it.
 */
@Environment(EnvType.CLIENT)
public class TeamLocatorClient implements ClientModInitializer {
    public static final TeamConfig CONFIG = TeamConfig.load();
    public static final RelayClient RELAY =
            new RelayClient(() -> CONFIG.effectiveSharingSet(), () -> CONFIG.blockedSet());

    /** Send our own position every 4 client ticks (5 Hz), matching the old broadcast interval. */
    private static final int POSITION_INTERVAL_TICKS = 4;

    private static KeyMapping pingKey;
    private int positionTickCounter;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("teamlocator", "main"));
        pingKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.teamlocator.ping", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, category));

        HudElementRegistry.addLast(TeamHud.ID, new TeamHud(CONFIG));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            ClientState.reset();
            connectRelay();
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            RELAY.disconnect();
            ClientState.reset();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (pingKey.consumeClick()) {
                RELAY.sendAttackPing();
            }
            broadcastOwnPosition(client);
        });
    }

    /** The client now sources its own coordinates — they no longer come from a server mod. */
    private void broadcastOwnPosition(Minecraft client) {
        if (++positionTickCounter < POSITION_INTERVAL_TICKS) {
            return;
        }
        positionTickCounter = 0;
        LocalPlayer player = client.player;
        if (player == null || !RELAY.isReady()) {
            return;
        }
        RELAY.sendPosition(
                player.getX(), player.getY(), player.getZ(),
                player.level().dimension().identifier().toString());
    }

    /**
     * (Re)connect the relay for the current context. Called on server join and when the relay URL
     * changes in the config screen. No-op in singleplayer (nobody to share with) or when no relay
     * URL is configured.
     */
    public static void connectRelay() {
        String serverKey = TeamConfig.currentServerKey();
        if (serverKey == null || "singleplayer".equals(serverKey) || CONFIG.relayUrl.isBlank()) {
            RELAY.disconnect();
            return;
        }
        RELAY.connect(CONFIG.relayUrl, serverKey);
    }

    /**
     * Recompute the effective trust and block sets from config and push them to the relay. Call
     * after any config change so routing updates immediately. No-op when not connected.
     */
    public static void syncToServer() {
        if (!RELAY.isReady()) {
            return;
        }
        RELAY.sendTrust(CONFIG.effectiveSharingSet());
        RELAY.sendBlocked(CONFIG.blockedSet());
    }
}
