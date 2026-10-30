package dev.spog.teamlocator.client;

import dev.spog.teamlocator.TeamLocatorConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A player's skin by UUID, whether or not they are in our tab list.
 *
 * <p>The tab list only holds players on our own server, so a teammate on another backend of the
 * same network -- a different shard, say -- drew as the default skin. When the tab list has no
 * entry, their profile is fetched from Mojang once, in the background, and the skin cached; the
 * default skin stands in until it arrives, or for good if the lookup fails.
 */
@Environment(EnvType.CLIENT)
public final class Skins {
    private static final Map<UUID, PlayerSkin> fetched = new ConcurrentHashMap<>();
    /** Every player a lookup has been started for, so each is only asked about once. */
    private static final Set<UUID> requested = ConcurrentHashMap.newKeySet();

    private Skins() {
    }

    /** The tab list's skin, else the fetched one, else the default while (or if) that fails. */
    public static PlayerSkin of(Minecraft mc, UUID id) {
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(id);
            if (info != null && info.getSkin() != null) {
                return info.getSkin();
            }
        }
        PlayerSkin skin = fetched.get(id);
        if (skin != null) {
            return skin;
        }
        if (requested.add(id)) {
            fetch(mc, id);
        }
        return DefaultPlayerSkin.get(id);
    }

    private static void fetch(Minecraft mc, UUID id) {
        // The profile resolver blocks on Mojang's session server, so off the render thread.
        CompletableFuture.supplyAsync(() -> mc.services().profileResolver().fetchById(id))
                .thenCompose(profile -> profile
                        .map(p -> mc.getSkinManager().get(p))
                        .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty())))
                .thenAccept(skin -> skin.ifPresent(s -> fetched.put(id, s)))
                .exceptionally(e -> {
                    TeamLocatorConstants.LOGGER.debug("Could not fetch the skin of {}: {}", id, e.toString());
                    return null;
                });
    }
}
