package dev.spog.teamlocator.mixin.client;

import dev.spog.teamlocator.client.RelayLang;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps relay's strings in step with the vanilla language switcher.
 *
 * <p>Relay's table is loaded from the jar rather than through the {@code ResourceManager}, so it
 * does not take part in resource reloads on its own. Hooking the language manager's reload is what
 * makes the normal Options -> Language flow work: picking a language reloads resources, this fires,
 * and relay re-reads its table for the newly selected code.
 *
 * <p>Injected at RETURN of both entry points so {@code currentCode} is already updated when
 * {@link RelayLang#reload()} reads it -- at HEAD it would still hold the previous language and the
 * strings would lag one change behind.
 */
@Mixin(LanguageManager.class)
public abstract class LanguageManagerMixin {
    @Inject(method = "onResourceManagerReload", at = @At("RETURN"))
    private void relay$reloadOnResourceReload(ResourceManager resourceManager, CallbackInfo ci) {
        // Forced: a reload can swap the files behind an unchanged language code.
        RelayLang.reload(true);
    }

    @Inject(method = "setSelected", at = @At("RETURN"))
    private void relay$reloadOnLanguageSelected(String code, CallbackInfo ci) {
        RelayLang.reload();
    }
}
