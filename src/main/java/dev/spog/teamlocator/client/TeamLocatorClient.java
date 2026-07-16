package dev.spog.teamlocator.client;

import net.minecraft.client.util.InputUtil;
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
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/**
 * Client entrypoint. The mod is client-only: coordinates and pings travel through the user's relay
 * service (see {@code relay/}), never through the MinecraftClient server, so it works on any server. This
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

    private static KeyBinding pingKey;
    private static KeyBinding removeTargetKey;
    private static KeyBinding configKey;
    private static KeyBinding hudToggleKey;
    private static KeyBinding inWorldIconsToggleKey;
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
        KeyBinding.Category category = new KeyBinding.Category(
                Identifier.of(TeamLocatorConstants.MOD_ID, "main"));
        pingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.relay.ping", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, category));
        int unbound = InputUtil.UNKNOWN_KEY.getCode();
        removeTargetKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.relay.remove_target", InputUtil.Type.KEYSYM, unbound, category));
        configKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.relay.open_config", InputUtil.Type.KEYSYM, unbound, category));
        hudToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.relay.toggle_hud", InputUtil.Type.KEYSYM, unbound, category));
        inWorldIconsToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.relay.toggle_in_world_icons", InputUtil.Type.KEYSYM, unbound, category));

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
            while (pingKey.wasPressed()) {
                RELAY.sendAttackPing();
            }
            while (removeTargetKey.wasPressed()) {
                removeTargetedPlayer(client);
            }
            while (configKey.wasPressed()) {
                client.setScreen(new TeamLocatorConfigScreen(null, CONFIG));
            }
            while (hudToggleKey.wasPressed()) {
                toggleHud();
            }
            while (inWorldIconsToggleKey.wasPressed()) {
                toggleInWorldIcons();
            }
            broadcastOwnPosition(client);
        });
    }

    /** Remove the player under the crosshair from the active trust list (keybind action). */
    private static void removeTargetedPlayer(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return;
        }
        AbstractClientPlayerEntity target = playerUnderCrosshair(client, player);
        TeamConfig.TrustList list = CONFIG.activeList();
        if (target == null || list == null) {
            return;
        }
        UUID id = target.getUuid();
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
            RelayChat.send(Text.translatable("relay.remove_target.removed",
                    RelayChat.value(target.getName().getString())));
        } else {
            RelayChat.send(Text.translatable("relay.remove_target.not_listed",
                    RelayChat.value(target.getName().getString())));
        }
    }

    /**
     * The nearest player intersecting the crosshair ray. Vanilla's {@code crosshairPickEntity}
     * only reaches melee range, so cast our own ray against slightly-inflated player boxes —
     * a teammate being pointed at across a field should still be removable.
     */
    private static AbstractClientPlayerEntity playerUnderCrosshair(MinecraftClient client, ClientPlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVec(1.0f).multiply(TARGET_RANGE));
        AbstractClientPlayerEntity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (AbstractClientPlayerEntity candidate : client.world.getPlayers()) {
            if (candidate == player || candidate.isSpectator()) {
                continue;
            }
            var hit = candidate.getBoundingBox().expand(0.3).raycast(eye, end);
            if (hit.isEmpty()) {
                continue;
            }
            double distSq = hit.get().squaredDistanceTo(eye);
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
        RelayChat.send(Text.translatable(
                CONFIG.hudEnabled ? "relay.hud.shown" : "relay.hud.hidden"));
    }

    /**
     * Flip the Xaero in-world icon config option (keybind action). The trackers and the renderer
     * mixin read the config live, so the icons appear/disappear on the next frame.
     */
    private static void toggleInWorldIcons() {
        CONFIG.xaeroInWorldIcons = !CONFIG.xaeroInWorldIcons;
        CONFIG.save();
        RelayChat.send(Text.translatable(
                CONFIG.xaeroInWorldIcons ? "relay.in_world_icons.shown" : "relay.in_world_icons.hidden"));
    }

    /** The client now sources its own coordinates — they no longer come from a server mod. */
    private void broadcastOwnPosition(MinecraftClient client) {
        if (++positionTickCounter < POSITION_INTERVAL_TICKS) {
            return;
        }
        positionTickCounter = 0;
        ClientPlayerEntity player = client.player;
        if (player == null || !sharePositions || !RELAY.isReady()) {
            return;
        }
        RELAY.sendPosition(
                player.getX(), player.getY(), player.getZ(),
                player.getEntityWorld().getRegistryKey().getValue().toString());
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
