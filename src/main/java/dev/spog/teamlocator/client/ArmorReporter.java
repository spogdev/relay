package dev.spog.teamlocator.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Watches our own armor and reports it to the relay for teammates' HUDs.
 *
 * <p>Sends only on change rather than on a timer: armor changes on equip or damage, so a stationary
 * fully-geared player costs nothing, while a piece taking a hit propagates on the next tick. The
 * relay stores the last report per session and replays it to late viewers, so "only on change" does
 * not mean a viewer who connects later sees nothing.
 *
 * <p>Sharing is opt-in ({@link dev.spog.teamlocator.client.config.TeamConfig#shareArmor}). Turning
 * it off sends one empty update to retract what the relay already holds, so opting out takes effect
 * immediately rather than at the next reconnect.
 */
@Environment(EnvType.CLIENT)
public final class ArmorReporter {
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    /** Last payload sent, for change detection. Null until the first send. */
    private List<ArmorPiece> lastSent;
    /** Whether the last send was a retraction, so we retract exactly once when sharing goes off. */
    private boolean retracted = true;

    /** Re-read our armor and push it if it changed. Called every position tick. */
    public void tick(LocalPlayer player, boolean shareArmor) {
        if (!shareArmor) {
            // Opted out: retract once, then stay quiet until sharing comes back on.
            if (!retracted) {
                retracted = true;
                lastSent = null;
                TeamLocatorClient.RELAY.sendArmor(List.of());
            }
            return;
        }
        List<ArmorPiece> current = read(player);
        if (retracted || !current.equals(lastSent)) {
            retracted = false;
            lastSent = current;
            TeamLocatorClient.RELAY.sendArmor(current);
        }
    }

    /** Forget what we believe the relay holds, so the next tick re-sends it. */
    public void reset() {
        lastSent = null;
        retracted = true;
    }

    private static List<ArmorPiece> read(LocalPlayer player) {
        List<ArmorPiece> pieces = new ArrayList<>(SLOTS.length);
        for (EquipmentSlot slot : SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) {
                continue;
            }
            // A non-damageable piece (carved pumpkin, elytra with no durability component) reports
            // maxDamage 0, which the HUD renders as an icon with no bar.
            int maxDamage = stack.isDamageableItem() ? stack.getMaxDamage() : 0;
            int damage = maxDamage > 0 ? stack.getDamageValue() : 0;
            pieces.add(new ArmorPiece(slot.getName(), id.toString(), damage, maxDamage));
        }
        return List.copyOf(pieces);
    }
}
