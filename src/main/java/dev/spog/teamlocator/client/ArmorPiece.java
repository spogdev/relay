package dev.spog.teamlocator.client;

import net.minecraft.world.item.ItemStack;

/**
 * One of a teammate's equipped armor pieces, as reported through the relay.
 *
 * <p>Carries the item's registry id rather than a resolved {@code Item}: the value arrives off the
 * network thread, and an id naming an item this client doesn't have (a modded piece the sender has
 * and we don't) must degrade to "skip that piece", not throw. Resolution happens at render time in
 * {@code TeamHud}.
 *
 * @param maxDamage the item's durability, or 0 for a piece with no durability bar (unbreakable, or
 *                  a non-damageable item like a carved pumpkin) — mirrors {@link ItemStack}
 */
public record ArmorPiece(String slot, String item, int damage, int maxDamage) {
    /** Head-to-feet, matching how the inventory stacks the armor column. */
    public static final String[] SLOT_ORDER = {"head", "chest", "legs", "feet"};

    /** True if this piece should show a durability bar; mirrors {@code ItemStack.isDamageableItem}. */
    public boolean hasDurability() {
        return maxDamage > 0;
    }

    /** Remaining durability as a 0..1 fraction; 1 when the piece has no durability bar. */
    public float durabilityFraction() {
        if (!hasDurability()) {
            return 1.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, (maxDamage - damage) / (float) maxDamage));
    }
}
