package dev.spog.teamlocator.client;

import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * A teammate's last known position as reported by the relay. Rendered by the HUD.
 */
public record TrackedPos(UUID id, double x, double y, double z, Identifier dimension) {
}
