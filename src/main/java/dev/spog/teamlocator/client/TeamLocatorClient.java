package dev.spog.teamlocator.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.spog.teamlocator.client.config.TeamConfig;
import dev.spog.teamlocator.client.hud.TeamHud;
import dev.spog.teamlocator.net.BlockUpdatePayload;
import dev.spog.teamlocator.net.PingBroadcastPayload;
import dev.spog.teamlocator.net.PingPayload;
import dev.spog.teamlocator.net.PositionSnapshotPayload;
import dev.spog.teamlocator.net.TrustUpdatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Client entrypoint: loads config, registers the attack-ping keybind and the HUD, receives
 * position/ping packets from the server, and pushes the client's effective trust and block sets to
 * the server whenever they change (on join and after any config edit).
 */
@Environment(EnvType.CLIENT)
public class TeamLocatorClient implements ClientModInitializer {
    public static final TeamConfig CONFIG = TeamConfig.load();

    private static KeyMapping pingKey;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("teamlocator", "main"));
        pingKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.teamlocator.ping", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, category));

        HudElementRegistry.addLast(TeamHud.ID, new TeamHud(CONFIG));

        ClientPlayNetworking.registerGlobalReceiver(PositionSnapshotPayload.ID, (payload, context) ->
                ClientState.setLatest(payload.entries()));

        ClientPlayNetworking.registerGlobalReceiver(PingBroadcastPayload.ID, (payload, context) ->
                ClientState.flagAttacked(payload.attacker()));

        // Push our trust/block state to the server once the play connection is ready.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            ClientState.reset();
            syncToServer();
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientState.reset());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (pingKey.consumeClick()) {
                if (ClientPlayNetworking.canSend(PingPayload.ID)) {
                    ClientPlayNetworking.send(PingPayload.INSTANCE);
                }
            }
        });
    }

    /**
     * Recompute the effective trust and block sets from config and send them to the server. Call
     * after any config change so routing updates immediately. No-op when not connected.
     */
    public static void syncToServer() {
        if (!ClientPlayNetworking.canSend(TrustUpdatePayload.ID)) {
            return;
        }
        ClientPlayNetworking.send(new TrustUpdatePayload(CONFIG.effectiveSharingSet()));
        ClientPlayNetworking.send(new BlockUpdatePayload(CONFIG.blockedSet()));
    }
}
