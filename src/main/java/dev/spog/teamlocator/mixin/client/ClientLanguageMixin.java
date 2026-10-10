package dev.spog.teamlocator.mixin.client;

import dev.spog.teamlocator.client.RelayLang;
import net.minecraft.client.resource.language.TranslationStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Serves relay's keybind strings without putting them in the translation table.
 *
 * <p>Relay's UI strings live in {@link RelayLang} and never reach the game's table at all. Keybinds
 * cannot be handled that way: vanilla's Controls screen renders an entry by calling
 * {@code Text.translatable} on the keybind's own id, and the category header on
 * {@code key.category.<namespace>.<path>}, so with no entry the screen would display the raw
 * {@code key.relay.ping} instead of a name. Shipping those entries in a lang file would hand every
 * server a probe, and renaming them only changes what the probe asks for.
 *
 * <p>So the entries are not shipped, and this fills them in at lookup time instead — but only while
 * relay's own UI is the caller, which {@link RelayLang#isResolvingKeybinds()} tracks. A key probe
 * arrives through a server-sent text, outside that window, and sees exactly what a vanilla client
 * shows: a key that does not resolve. The strings remain absent from {@code translations}, so
 * anything that walks the map directly does not find them either.
 *
 * <p>Both lookup methods are covered because they are the whole probe surface: resolving text goes
 * through {@code get}, and a fallback-carrying text is decided by {@code hasTranslation}. Leaving
 * either unguarded would leak the keys through the other.
 */
@Mixin(TranslationStorage.class)
public abstract class ClientLanguageMixin {
    @Inject(method = "get(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            at = @At("HEAD"), cancellable = true)
    private void relay$serveKeybindName(String key, String fallback,
                                        CallbackInfoReturnable<String> cir) {
        if (RelayLang.isResolvingKeybinds() && RelayLang.hasKeybind(key)) {
            cir.setReturnValue(RelayLang.keybind(key));
        }
    }

    @Inject(method = "hasTranslation(Ljava/lang/String;)Z", at = @At("HEAD"), cancellable = true)
    private void relay$hasKeybindName(String key, CallbackInfoReturnable<Boolean> cir) {
        if (RelayLang.isResolvingKeybinds() && RelayLang.hasKeybind(key)) {
            cir.setReturnValue(true);
        }
    }
}
