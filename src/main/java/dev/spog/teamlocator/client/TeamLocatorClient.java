package dev.spog.teamlocator.client;

import net.minecraft.client.util.InputUtil;
import dev.spog.teamlocator.TeamLocatorConstants;
import dev.spog.teamlocator.client.compat.xaero.XaeroCompat;
import dev.spog.teamlocator.client.config.TeamConfig;
import dev.spog.teamlocator.client.config.TrustEntry;
import dev.spog.teamlocator.client.render.PingRenderer;

import net.minecraft.world.RaycastContext;
import net.minecraft.util.hit.HitResult;
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

    /** How far the toggle-target keybind searches along the crosshair ray, in blocks. */
    private static final double TARGET_RANGE = 64.0;

    private static KeyBinding pingKey;
    private static KeyBinding toggleTargetKey;
    private static KeyBinding mapPingKey;
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
        int unbound = InputUtil.UNKNOWN_KEY.getCode();
        // Unbound by default, like the rest: an accidental alert pings every mutually-trusted
        // teammate, so sending one should be a deliberate binding choice, not a default key.
        pingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.relay.ping", InputUtil.Type.KEYSYM, unbound, category));
        toggleTargetKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.relay.toggle_target", InputUtil.Type.KEYSYM, unbound, category));
        configKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.relay.open_config", InputUtil.Type.KEYSYM, unbound, category));
        hudToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.relay.toggle_hud", InputUtil.Type.KEYSYM, unbound, category));
        inWorldIconsToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.relay.toggle_in_world_icons", InputUtil.Type.KEYSYM, unbound, category));
        mapPingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.relay.map_ping", InputUtil.Type.KEYSYM, unbound, category));

        HudElementRegistry.addLast(TeamHud.ID, new TeamHud(CONFIG));

        // Register our custom alert sound event (client-only mod, safe at client init).
        RelaySounds.register();

        // /available — who would actually see an alert sent right now.
        AvailableCommand.register();
        RelayAdminCommand.register();
        RelayWaypointCommand.register();
        PingRenderer.register();
        // The mod's own in-world teammate icons; the default, so they work with no other mod. Steps
        // aside per-frame when the player prefers Xaero's and Xaero is installed.
        dev.spog.teamlocator.client.render.TeammateIconRenderer.register();

        // Optional: show tracked teammates on Xaero's Minimap / World Map when installed.
        XaeroCompat.init();

        // The relay connection is held for the whole client lifetime — including the title screen
        // and singleplayer — so cross-server attack pings arrive anywhere. Only the scope changes.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> connectRelay());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            ClientState.reset();
            PingState.reset();
            // A new connection means the relay holds no armor for us; re-send on the next tick.
            ARMOR_REPORTER.reset();
            // Restore this server's remembered active list before connecting, so the very first
            // trust push already carries the right set.
            CONFIG.onScopeChanged();
            // Vanilla just called Mojang's joinServer to enter this world, and authlib shares one
            // rate limiter between that and our relay auth. Tell the relay so its first auth here
            // yields the limiter to vanilla rather than racing it — otherwise one of the two gets
            // "RateLimiter disallowed request", and if vanilla loses, this very join can fail.
            RELAY.noteServerJoin();
            connectRelay();
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            ClientState.reset();
            PingState.reset();
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
            while (toggleTargetKey.wasPressed()) {
                toggleTargetedPlayer(client);
            }
            while (mapPingKey.wasPressed()) {
                placeMapPing(client);
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

    /**
     * Toggle trust for the player under the crosshair in the active list (keybind action): if they
     * are already trusted, remove them; if not, add them. One key for both directions, so a player
     * can trust someone they're looking at and untrust them the same way.
     */
    private static void toggleTargetedPlayer(MinecraftClient client) {
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
        String name = target.getName().getString();
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
            RelayChat.send(Text.translatable("relay.toggle_target.removed", RelayChat.value(name)));
        } else {
            list.trusted.add(new TrustEntry(id, name));
            CONFIG.save();
            syncToServer();
            RelayChat.send(Text.translatable("relay.toggle_target.added", RelayChat.value(name)));
        }
    }

    /**
     * Place a ping where the player is looking — or withdraw their existing one, if the key is
     * pressed while its labels are showing.
     *
     * <p>The remove case is gated on the crosshair actually being over your own ping, which is the
     * same condition that draws its name and distance. So the labels appearing are the cue that the
     * key now removes rather than places: there is no hidden mode, and the affordance is on screen
     * exactly when it applies. Anywhere else, the key does what it always did.
     */
    private static void placeMapPing(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || !RELAY.isReady()) {
            return;
        }
        UUID self = client.getSession().getUuidOrNull();
        if (PingState.has(self) && PingRenderer.isHovered(self)) {
            // Clear locally at once so the key feels immediate, and tell the relay so it clears for
            // teammates too — otherwise the marker would linger on their screens until it expired.
            PingState.remove(self);
            RELAY.sendRemoveMapPing();
            return;
        }
        double range = client.options.getClampedViewDistance() * 16.0;
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVec(1.0f).multiply(range));
        var hit = client.world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return; // nothing within the loaded world; no ping, no message
        }
        Vec3d at = hit.getPos();
        String color = PingPalette.forPlayer(client.getSession().getUuidOrNull(), CONFIG.pingColorIndex);
        String dimension = player.getEntityWorld().getRegistryKey().getValue().toString();
        RELAY.sendMapPing(at.x, at.y, at.z, dimension, color);
        // Show our own ping immediately: the relay never echoes it back to us, and a marker that
        // only teammates could see would make the keybind feel broken.
        PingState.put(new PingState.Ping(client.getSession().getUuidOrNull(), at.x, at.y, at.z,
                player.getEntityWorld().getRegistryKey().getValue(),
                0xFF000000 | Integer.parseInt(color.substring(1), 16),
                System.currentTimeMillis()));
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
     * Flip which renderer draws teammates' in-world icons: the mod's own (default) or Xaero's
     * (keybind action). The renderer and the tracker mixin read the config live, so the swap takes
     * effect on the next frame.
     *
     * <p>Xaero's icon exists only where Xaero's Minimap is installed. With it absent the setting has
     * nothing to switch to, so the toggle is a no-op and says so rather than silently flipping a flag
     * that changes nothing on screen.
     */
    private static void toggleInWorldIcons() {
        if (!XaeroCompat.isMinimapInstalled()) {
            RelayChat.send(Text.translatable("relay.in_world_icons.xaero_missing"));
            return;
        }
        CONFIG.useXaeroInWorldIcons = !CONFIG.useXaeroInWorldIcons;
        CONFIG.save();
        RelayChat.send(Text.translatable(CONFIG.useXaeroInWorldIcons
                ? "relay.in_world_icons.using_xaero" : "relay.in_world_icons.using_builtin"));
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
                player.getEntityWorld().getRegistryKey().getValue().toString(),
                // Health rides the position update, gated by the same sharing trust — a player who
                // shares no position shares no health either — plus its own opt-out. Sending the
                // unknown marker rather than omitting the field means opting out actively clears
                // what the relay already holds, instead of leaving a stale value on teammates'
                // screens until reconnect.
                CONFIG.shareHealth ? player.getHealth() : TrackedPos.UNKNOWN_HEALTH);
        // Same gate and cadence as the position, but the reporter only actually sends when a piece
        // changes, so a geared player standing still costs nothing.
        ARMOR_REPORTER.tick(player, CONFIG.shareArmor);
    }

    /**
     * (Re)connect the relay for the current context: the joined server's scope in multiplayer, or
     * the shared "menu"/"singleplayer" scopes otherwise (connected for pings, never sending
     * positions). Called at client start, on join/disconnect, and when the relay URL changes in
     * the config screen. Disconnects only when no relay URL is configured.
     *
     * <p>Goes through {@link RelayClient#setScope}, which reuses the live authenticated socket when
     * the only thing that changed is the server — a transfer between servers on the same account then
     * costs one small {@code set-scope} frame, not a fresh Mojang joinServer. It falls back to a full
     * connect on the first connection, a relay URL change, or an account switch. This is what keeps
     * the mod off authlib's shared rate limiter on every server join.
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
        RELAY.setScope(CONFIG.relayWebSocketUrl(), scope);
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
