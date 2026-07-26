package dev.spog.teamlocator.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.spog.teamlocator.TeamLocatorConstants;
import dev.spog.teamlocator.client.compat.xaero.XaeroCompat;
import dev.spog.teamlocator.client.config.TeamConfig;
import dev.spog.teamlocator.client.config.TrustEntry;
import dev.spog.teamlocator.client.render.PingRenderer;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
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

    private static KeyMapping pingKey;
    private static KeyMapping toggleTargetKey;
    private static KeyMapping mapPingKey;
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
        int unbound = InputConstants.UNKNOWN.getValue();
        // Unbound by default, like the rest: an accidental alert pings every mutually-trusted
        // teammate, so sending one should be a deliberate binding choice, not a default key.
        pingKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.relay.ping", InputConstants.Type.KEYSYM, unbound, category));
        toggleTargetKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.relay.toggle_target", InputConstants.Type.KEYSYM, unbound, category));
        configKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.relay.open_config", InputConstants.Type.KEYSYM, unbound, category));
        hudToggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.relay.toggle_hud", InputConstants.Type.KEYSYM, unbound, category));
        inWorldIconsToggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.relay.toggle_in_world_icons", InputConstants.Type.KEYSYM, unbound, category));
        mapPingKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.relay.map_ping", InputConstants.Type.KEYSYM, unbound, category));

        HudElementRegistry.addLast(TeamHud.ID, new TeamHud(CONFIG));

        // Register our custom alert sound event (client-only mod, safe at client init).
        RelaySounds.register();

        // /available — who would actually see an alert sent right now.
        AvailableCommand.register();
        RelayAdminCommand.register();
        RelayWaypointCommand.register();
        PingRenderer.register();

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
            while (pingKey.consumeClick()) {
                RELAY.sendAttackPing();
            }
            while (toggleTargetKey.consumeClick()) {
                toggleTargetedPlayer(client);
            }
            while (mapPingKey.consumeClick()) {
                placeMapPing(client);
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

    /**
     * Toggle trust for the player under the crosshair in the active list (keybind action): if they
     * are already trusted, remove them; if not, add them. One key for both directions, so a player
     * can trust someone they're looking at and untrust them the same way.
     */
    private static void toggleTargetedPlayer(Minecraft client) {
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
            RelayChat.send(Component.translatable("relay.toggle_target.removed", RelayChat.value(name)));
        } else {
            list.trusted.add(new TrustEntry(id, name));
            CONFIG.save();
            syncToServer();
            RelayChat.send(Component.translatable("relay.toggle_target.added", RelayChat.value(name)));
        }
    }

    /**
     * Place a location ping where the player is looking (keybind action).
     *
     * <p>Ranged by the client's render distance rather than a fixed number: a ping beyond the
     * loaded world would mark a place the viewer cannot see anyway, and the blocks to hit are not
     * even loaded to be hit. Looking at open sky simply does nothing — deliberately silent, since a
     * failure message on every mis-aimed press would be noise in a fight.
     */
    /**
     * Place a ping where the player is looking — or withdraw their existing one, if the key is
     * pressed while its labels are showing.
     *
     * <p>The remove case is gated on the crosshair actually being over your own ping, which is the
     * same condition that draws its name and distance. So the labels appearing are the cue that the
     * key now removes rather than places: there is no hidden mode, and the affordance is on screen
     * exactly when it applies. Anywhere else, the key does what it always did.
     */
    private static void placeMapPing(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || !RELAY.isReady()) {
            return;
        }
        UUID self = client.getUser().getProfileId();
        if (PingState.has(self) && PingRenderer.isHovered(self)) {
            // Clear locally at once so the key feels immediate, and tell the relay so it clears for
            // teammates too — otherwise the marker would linger on their screens until it expired.
            PingState.remove(self);
            RELAY.sendRemoveMapPing();
            return;
        }
        double range = client.options.getEffectiveRenderDistance() * 16.0;
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(range));
        var hit = client.level.clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return; // nothing within the loaded world; no ping, no message
        }
        Vec3 at = hit.getLocation();
        String color = PingPalette.forPlayer(client.getUser().getProfileId(), CONFIG.pingColorIndex);
        String dimension = player.level().dimension().identifier().toString();
        RELAY.sendMapPing(at.x, at.y, at.z, dimension, color);
        // Show our own ping immediately: the relay never echoes it back to us, and a marker that
        // only teammates could see would make the keybind feel broken.
        PingState.put(new PingState.Ping(client.getUser().getProfileId(), at.x, at.y, at.z,
                player.level().dimension().identifier(),
                0xFF000000 | Integer.parseInt(color.substring(1), 16),
                System.currentTimeMillis()));
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
                player.level().dimension().identifier().toString(),
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
