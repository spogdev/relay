package dev.spog.teamlocator.client;

import net.minecraft.util.Identifier;

import java.util.List;
import java.util.UUID;

/**
 * A teammate's last known position and armor as reported by the relay. Rendered by the HUD.
 *
 * <p>{@code armor} is empty when the teammate doesn't share it (opted out, or an older client) —
 * distinct from them sharing an empty set while wearing nothing, which the HUD renders the same way
 * anyway. The list is immutable so this record's {@code equals} stays a reliable value comparison:
 * {@link ClientState#updatePosition} leans on it to keep a stable instance for Xaero's identity
 * check, and armor changing must count as a real change there.
 *
 * <p>{@code world} says which server and world of a network they are on -- see
 * {@link WorldIdentity} -- or is null when not known: an older client, or an older relay.
 */
public record TrackedPos(UUID id, double x, double y, double z, Identifier dimension,
                         List<ArmorPiece> armor, float health, String pingColor, String world) {
    /** Health value meaning "not reported" — an older client, or one yet to send a position. */
    public static final float UNKNOWN_HEALTH = -1.0f;

    public TrackedPos {
        armor = armor == null ? List.of() : List.copyOf(armor);
    }

    /** Convenience for the common case of a position with no armor, health or colour attached. */
    public TrackedPos(UUID id, double x, double y, double z, Identifier dimension) {
        this(id, x, y, z, dimension, List.of(), UNKNOWN_HEALTH, null, null);
    }

    /** A position from a teammate whose server is not known, so taken to be ours. */
    public TrackedPos(UUID id, double x, double y, double z, Identifier dimension,
                      List<ArmorPiece> armor, float health, String pingColor) {
        this(id, x, y, z, dimension, armor, health, pingColor, null);
    }

    /**
     * Whether they are on the same server as us, given our own {@link WorldIdentity}. Unknown on
     * either side counts as the same, which is how it was before servers could be told apart.
     */
    public boolean onServer(String ours) {
        return world == null || ours == null || world.equals(ours);
    }

    /** Whether this teammate is reporting health at all. */
    public boolean hasHealth() {
        return health >= 0.0f;
    }

    /**
     * This teammate's ping colour as ARGB, or {@code fallback} when they report none — an older
     * client, or one that has not sent a position since connecting.
     */
    public int nameColor(int fallback) {
        return pingColor == null ? fallback : PingPalette.argb(pingColor);
    }
}
