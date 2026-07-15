package dev.spog.teamlocator.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.spog.teamlocator.TeamLocatorConstants;
import dev.spog.teamlocator.client.config.TeamConfig;
import dev.spog.teamlocator.client.gui.TeamLocatorConfigScreen;
import dev.spog.teamlocator.client.hud.TeamHud;
import dev.spog.teamlocator.client.net.RelayClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/**
 * Client entrypoint. The mod is client-only: coordinates and pings travel through the user's relay
 * service (see {@code relay/}), never through the Minecraft server, so it works on any server. This
 * class loads config, registers the keybind and HUD, connects the relay when joining a server, and
 * pushes our own position / trust state to it.
 */
@Environment(EnvType.CLIENT)
public class TeamLocatorClient implements ClientModInitializer {
    public static final TeamConfig CONFIG = TeamConfig.load();
    public static final RelayClient RELAY = new RelayClient(
            () -> CONFIG.effectiveSharingSet(), () -> CONFIG.alertTrustSet());

    /** Send our own position every 4 client ticks (5 Hz), matching the old broadcast interval. */
    private static final int POSITION_INTERVAL_TICKS = 4;

    /** How far the remove-target keybind searches along the crosshair ray, in blocks. */
    private static final double TARGET_RANGE = 64.0;

    private static KeyMapping pingKey;
    private static KeyMapping removeTargetKey;
    private static KeyMapping configKey;
    private static KeyMapping hudToggleKey;
    private int positionTickCounter;

    /**
     * True only when connected under a real multiplayer-server scope. At the menu or in
     * singleplayer the relay connection stays up (to receive cross-server pings) but our own
     * coordinates are never sent — the "menu"/"singleplayer" scopes are shared by every user of
     * the relay, and positions must not leak between unrelated worlds.
     */
    private static volatile boolean sharePositions;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(TeamLocatorConstants.MOD_ID, "main"));
        pingKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.relay.ping", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, category));
        int unbound = InputConstants.UNKNOWN.getValue();
        removeTargetKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.relay.remove_target", InputConstants.Type.KEYSYM, unbound, category));
        configKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.relay.open_config", InputConstants.Type.KEYSYM, unbound, category));
        hudToggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.relay.toggle_hud", InputConstants.Type.KEYSYM, unbound, category));

        HudElementRegistry.addLast(TeamHud.ID, new TeamHud(CONFIG));

        // The relay connection is held for the whole client lifetime — including the title screen
        // and singleplayer — so cross-server attack pings arrive anywhere. Only the scope changes.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> connectRelay());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            ClientState.reset();
            connectRelay();
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            ClientState.reset();
            connectRelay(); // back to the "menu" scope, not a full disconnect
        }));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (pingKey.consumeClick()) {
                RELAY.sendAttackPing();
            }
            while (removeTargetKey.consumeClick()) {
                removeTargetedPlayer(client);
            }
            while (configKey.consumeClick()) {
                client.setScreen(new TeamLocatorConfigScreen(null, CONFIG));
            }
            while (hudToggleKey.consumeClick()) {
                toggleHud(client);
            }
            broadcastOwnPosition(client);
        });
    }

    /** Remove the player under the crosshair from the active trust list (keybind action). */
    private static void removeTargetedPlayer(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        AbstractClientPlayer target = playerUnderCrosshair(client, player);
        TeamConfig.TrustList list = CONFIG.activeList();
        if (target == null || list == null) {
            return;
        }
        UUID id = target.getUUID();
        boolean removed = list.trusted.removeIf(e -> {
            try {
                return e.uuid().equals(id);
            } catch (RuntimeException ex) {
                return false; // corrupt uuid string in config; leave it alone
            }
        });
        if (removed) {
            CONFIG.save();
            syncToServer();
            player.sendOverlayMessage(
                    Component.translatable("relay.remove_target.removed", target.getName()));
        } else {
            player.sendOverlayMessage(
                    Component.translatable("relay.remove_target.not_listed", target.getName()));
        }
    }

    /**
     * The nearest player intersecting the crosshair ray. Vanilla's {@code crosshairPickEntity}
     * only reaches melee range, so cast our own ray against slightly-inflated player boxes —
     * a teammate being pointed at across a field should still be removable.
     */
    private static AbstractClientPlayer playerUnderCrosshair(Minecraft client, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(TARGET_RANGE));
        AbstractClientPlayer best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (AbstractClientPlayer candidate : client.level.players()) {
            if (candidate == player || candidate.isSpectator()) {
                continue;
            }
            var hit = candidate.getBoundingBox().inflate(0.3).clip(eye, end);
            if (hit.isEmpty()) {
                continue;
            }
            double distSq = hit.get().distanceToSqr(eye);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = candidate;
            }
        }
        return best;
    }

    private static void toggleHud(Minecraft client) {
        CONFIG.hudEnabled = !CONFIG.hudEnabled;
        CONFIG.save();
        if (client.player != null) {
            client.player.sendOverlayMessage(Component.translatable(
                    CONFIG.hudEnabled ? "relay.hud.shown" : "relay.hud.hidden"));
        }
    }

    /** The client now sources its own coordinates — they no longer come from a server mod. */
    private void broadcastOwnPosition(Minecraft client) {
        if (++positionTickCounter < POSITION_INTERVAL_TICKS) {
            return;
        }
        positionTickCounter = 0;
        LocalPlayer player = client.player;
        if (player == null || !sharePositions || !RELAY.isReady()) {
            return;
        }
        RELAY.sendPosition(
                player.getX(), player.getY(), player.getZ(),
                player.level().dimension().identifier().toString());
    }

    /**
     * (Re)connect the relay for the current context: the joined server's scope in multiplayer, or
     * the shared "menu"/"singleplayer" scopes otherwise (connected for pings, never sending
     * positions). Called at client start, on join/disconnect, and when the relay URL changes in
     * the config screen. Disconnects only when no relay URL is configured.
     */
    public static void connectRelay() {
        if (CONFIG.relayUrl.isBlank()) {
            sharePositions = false;
            RELAY.disconnect();
            return;
        }
        String serverKey = TeamConfig.currentServerKey();
        String scope = serverKey != null ? serverKey : "menu";
        sharePositions = serverKey != null && !"singleplayer".equals(serverKey);
        RELAY.connect(CONFIG.relayWebSocketUrl(), scope);
    }

    /**
     * Recompute the effective trust sets from config and push them to the relay. Call after any
     * config change so routing updates immediately. No-op when not connected.
     */
    public static void syncToServer() {
        if (!RELAY.isReady()) {
            return;
        }
        RELAY.sendTrust(CONFIG.effectiveSharingSet(), CONFIG.alertTrustSet());
    }
}
