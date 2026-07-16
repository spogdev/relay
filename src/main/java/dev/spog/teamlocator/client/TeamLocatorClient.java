package dev.spog.teamlocator.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.spog.teamlocator.TeamLocatorConstants;
import dev.spog.teamlocator.client.compat.xaero.XaeroCompat;
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
    /** Declared before RELAY: RELAY's constructor captures it for the re-auth callback. */
    private static final ArmorReporter ARMOR_REPORTER = new ArmorReporter();
    public static final RelayClient RELAY = new RelayClient(
            () -> CONFIG.effectiveSharingSet(), () -> CONFIG.alertTrustSet(),
            // A fresh socket means the relay holds no armor for us: re-send it on the next tick.
            ARMOR_REPORTER::reset);

    /** Send our own position every 4 client ticks (5 Hz), matching the old broadcast interval. */
    private static final int POSITION_INTERVAL_TICKS = 4;

    /** How far the remove-target keybind searches along the crosshair ray, in blocks. */
    private static final double TARGET_RANGE = 64.0;

    private static KeyMapping pingKey;
    private static KeyMapping removeTargetKey;
    private static KeyMapping configKey;
    private static KeyMapping hudToggleKey;
    private static KeyMapping inWorldIconsToggleKey;
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
        inWorldIconsToggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.relay.toggle_in_world_icons", InputConstants.Type.KEYSYM, unbound, category));

        HudElementRegistry.addLast(TeamHud.ID, new TeamHud(CONFIG));

        // Register our custom alert sound event (client-only mod, safe at client init).
        RelaySounds.register();

        // Optional: show tracked teammates on Xaero's Minimap / World Map when installed.
        XaeroCompat.init();

        // The relay connection is held for the whole client lifetime — including the title screen
        // and singleplayer — so cross-server attack pings arrive anywhere. Only the scope changes.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> connectRelay());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            ClientState.reset();
            // A new connection means the relay holds no armor for us; re-send on the next tick.
            ARMOR_REPORTER.reset();
            // Restore this server's remembered active list before connecting, so the very first
            // trust push already carries the right set.
            CONFIG.onScopeChanged();
            connectRelay();
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            ClientState.reset();
            // Leaving a server: the server list is unreachable at the menu, so fall back to GLOBAL
            // without touching serverModes — the next join restores that server's own choice. Done
            // explicitly rather than via onScopeChanged() because getCurrentServer() may still be
            // populated at this point, which would re-pin the departed server's mode.
            CONFIG.activeMode = TeamConfig.Mode.GLOBAL;
            CONFIG.save();
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
                toggleHud();
            }
            while (inWorldIconsToggleKey.consumeClick()) {
                toggleInWorldIcons();
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
            RelayChat.send(Component.translatable("relay.remove_target.removed",
                    RelayChat.value(target.getName().getString())));
        } else {
            RelayChat.send(Component.translatable("relay.remove_target.not_listed",
                    RelayChat.value(target.getName().getString())));
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

    private static void toggleHud() {
        CONFIG.hudEnabled = !CONFIG.hudEnabled;
        CONFIG.save();
        RelayChat.send(Component.translatable(
                CONFIG.hudEnabled ? "relay.hud.shown" : "relay.hud.hidden"));
    }

    /**
     * Flip the Xaero in-world icon config option (keybind action). The trackers and the renderer
     * mixin read the config live, so the icons appear/disappear on the next frame.
     */
    private static void toggleInWorldIcons() {
        CONFIG.xaeroInWorldIcons = !CONFIG.xaeroInWorldIcons;
        CONFIG.save();
        RelayChat.send(Component.translatable(
                CONFIG.xaeroInWorldIcons ? "relay.in_world_icons.shown" : "relay.in_world_icons.hidden"));
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
        // Same gate and cadence as the position, but the reporter only actually sends when a piece
        // changes, so a geared player standing still costs nothing.
        ARMOR_REPORTER.tick(player, CONFIG.shareArmor);
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
