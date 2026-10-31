package dev.spog.teamlocator.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.UUID;

/** The name to show for a player, wherever they are. */
@Environment(EnvType.CLIENT)
public final class PlayerNames {
    private PlayerNames() {
    }

    /**
     * A test player's name, then the name cached on their trust-list entry (which works when they
     * are not on our server), then the tab list, then a UUID stub so a player is never nameless.
     */
    public static String displayName(Minecraft mc, UUID player) {
        String name = TestPlayers.name(player);
        if (name != null) {
            return name;
        }
        name = TeamLocatorClient.CONFIG.nameFor(player);
        if (name != null) {
            return name;
        }
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(player);
            if (info != null) {
                return info.getProfile().name();
            }
        }
        return player.toString().substring(0, 8);
    }
}
