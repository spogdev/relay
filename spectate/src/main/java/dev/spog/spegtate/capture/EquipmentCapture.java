package dev.spog.spegtate.capture;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Sends the target's own equipment, which its server never tells it about.
 *
 * <p>A player switching hotbar slot produced nothing for a viewer: the held item stayed at
 * whatever it was when the entity spawned. The cause is the same one behind the block-break
 * hook in {@link CaptureHooks} -- the server broadcasts equipment through
 * {@code sendToOtherNearbyPlayers}, which is every nearby player <em>except the entity
 * itself</em>, because that client already knows what it is holding. So the target's own
 * equipment changes are absent from its inbound stream and there is nothing to forward.
 *
 * <p>This fills the gap from the client's own state, as a real
 * {@link EntityEquipmentUpdateS2CPacket}, so the viewer applies it through vanilla's own handler
 * and needs no new frame type or viewer-side code.
 *
 * <p>Only the target's own equipment. Everyone else's arrives normally, because the target is
 * one of the "other" players from their point of view.
 */
public final class EquipmentCapture {
    /**
     * What was last sent, so an unchanged tick costs a comparison and nothing else.
     *
     * <p>Stacks are copied in. {@code getEquippedStack} hands back the live stack, which the
     * player then mutates in place -- so holding the reference would compare a stack against
     * itself and report no change however much it changed.
     */
    private final Map<EquipmentSlot, ItemStack> lastSent = new EnumMap<>(EquipmentSlot.class);

    /**
     * The packet to send, or null when nothing changed.
     *
     * <p>Carries only the slots that differ, which is what vanilla's own diff does: a hotbar
     * switch is one slot, not seven.
     */
    public EntityEquipmentUpdateS2CPacket packetIfChanged(MinecraftClient mc) {
        PlayerEntity player = mc.player;
        if (player == null) {
            return null;
        }
        List<Pair<EquipmentSlot, ItemStack>> changed = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            // A player has no body or saddle slot; asking for one throws on some versions and
            // is meaningless on all of them.
            if (slot == EquipmentSlot.BODY || slot == EquipmentSlot.SADDLE) {
                continue;
            }
            ItemStack now = player.getEquippedStack(slot);
            ItemStack before = lastSent.get(slot);
            // areEqual rather than ==: the stack is mutated in place, and a count or component
            // change is as visible to a viewer as a different item.
            if (before != null && ItemStack.areEqual(before, now)) {
                continue;
            }
            lastSent.put(slot, now.copy());
            changed.add(new Pair<>(slot, now.copy()));
        }
        if (changed.isEmpty()) {
            return null;
        }
        return new EntityEquipmentUpdateS2CPacket(player.getId(), changed);
    }

    /**
     * Forgets what was sent, so the next tick resends everything.
     *
     * <p>Called when a viewer joins: the equipment it needs was sent to the previous viewer, and
     * a diff against that would send nothing at all to the new one.
     */
    public void reset() {
        lastSent.clear();
    }
}
