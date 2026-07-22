package dev.spog.teamlocator.client;

import net.minecraft.resources.Identifier;

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
 */
public record TrackedPos(UUID id, double x, double y, double z, Identifier dimension,
                         List<ArmorPiece> armor, float health, String pingColor) {
    /** Health value meaning "not reported" — an older client, or one yet to send a position. */
    public static final float UNKNOWN_HEALTH = -1.0f;

    public TrackedPos {
        armor = armor == null ? List.of() : List.copyOf(armor);
    }

    /** Convenience for the common case of a position with no armor, health or colour attached. */
    public TrackedPos(UUID id, double x, double y, double z, Identifier dimension) {
        this(id, x, y, z, dimension, List.of(), UNKNOWN_HEALTH, null);
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
