package dev.spog.spegtate.mixin;

import dev.spog.spegtate.SpectateManager;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Limits the spectator bar to players a viewer can actually focus.
 *
 * <p>Vanilla builds the teleport menu from {@code getListedPlayerListEntries()} -- every player on
 * the server. On a viewer that list is the <em>target's</em> tab list, forwarded wholesale, so the
 * bar offered every player on the target's server. Almost none of them can be focused: the capture
 * streams only the entities around the target, so for the rest the viewer has a name and no entity,
 * and clicking one moved the camera nowhere.
 *
 * <p>So the list is narrowed to players the viewer actually has an entity for, which is exactly
 * the set the capture sends: those within the sweep radius of the target. The bar then shows who
 * can be watched and nothing else, and every entry on it works.
 *
 * <p>Applied by rewriting the collection the constructor was handed, rather than by filtering the
 * built menu: the entries are turned into commands in that constructor, so this is the last point
 * where the input is still a list of players.
 */
@Mixin(net.minecraft.client.gui.spectator.categories.TeleportToPlayerMenuCategory.class)
public abstract class SpectatorBarMixin {
    @ModifyVariable(method = "<init>(Ljava/util/Collection;)V", at = @At("HEAD"), argsOnly = true)
    private Collection<PlayerInfo> spegtate$onlyReachable(Collection<PlayerInfo> entries) {
        if (!SpectateManager.get().isViewing()) {
            return entries;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return entries;
        }
        java.util.Set<java.util.UUID> present = new java.util.HashSet<>();
        for (var player : mc.level.players()) {
            present.add(player.getUUID());
        }
        List<PlayerInfo> reachable = new ArrayList<>();
        for (PlayerInfo entry : entries) {
            if (entry.getProfile() != null && present.contains(entry.getProfile().id())) {
                reachable.add(entry);
            }
        }
        return reachable;
    }
}
