package dev.spog.teamlocator.client;

import dev.spog.teamlocator.TeamLocatorConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.registry.Registry;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.sound.SoundEvent;

/** Custom relay sound events. {@link #register()} must be called once at client init. */
@Environment(EnvType.CLIENT)
public final class RelaySounds {
    /** Played when a map ping arrives. */
    public static final Identifier PING_ID =
            Identifier.of(TeamLocatorConstants.MOD_ID, "ping");
    public static final SoundEvent PING = SoundEvent.of(PING_ID);

    private RelaySounds() {}

    public static void register() {
        Registry.register(Registries.SOUND_EVENT, PING_ID, PING);
    }
}
