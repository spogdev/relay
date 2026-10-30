package dev.spog.teamlocator.mixin.client;

import net.minecraft.world.biome.source.BiomeAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The world's hashed seed, which the client only keeps inside its biome manager. */
@Mixin(BiomeAccess.class)
public interface BiomeManagerAccessor {
    @Accessor("seed")
    long relay$biomeZoomSeed();
}
