package dev.spog.teamlocator.client;

import dev.spog.teamlocator.TeamLocatorConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/** Custom relay sound events. {@link #register()} must be called once at client init. */
@Environment(EnvType.CLIENT)
public final class RelaySounds {
    public static final Identifier ALARM_ID =
            Identifier.fromNamespaceAndPath(TeamLocatorConstants.MOD_ID, "alarm");
    public static final SoundEvent ALARM = SoundEvent.createVariableRangeEvent(ALARM_ID);

    /**
     * Played when a map ping arrives. Kept separate from the alert sound rather than folded into
     * that setting: a ping and a call for help are different events, and sharing one sound would
     * mean having to look at the screen to tell which just happened.
     */
    public static final Identifier PING_ID =
            Identifier.fromNamespaceAndPath(TeamLocatorConstants.MOD_ID, "ping");
    public static final SoundEvent PING = SoundEvent.createVariableRangeEvent(PING_ID);

    private RelaySounds() {}

    public static void register() {
        Registry.register(BuiltInRegistries.SOUND_EVENT, ALARM_ID, ALARM);
        Registry.register(BuiltInRegistries.SOUND_EVENT, PING_ID, PING);
    }
}
