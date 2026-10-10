package dev.spog.teamlocator.mixin.client;

import dev.spog.teamlocator.client.RelayLang;
import net.minecraft.client.resources.language.ClientLanguage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Serves relay's keybind strings without putting them in the translation table.
 *
 * <p>Relay's UI strings live in {@link RelayLang} and never reach the game's table at all. Keybinds
 * cannot be handled that way: vanilla's Controls screen renders an entry by calling
 * {@code Component.translatable} on the keybind's own id, and the category header on
 * {@code key.category.<namespace>.<path>}, so with no entry the screen would display the raw
 * {@code key.relay.ping} instead of a name. Shipping those entries in a lang file would hand every
 * server a probe, and renaming them only changes what the probe asks for.
 *
 * <p>So the entries are not shipped, and this fills them in at lookup time instead — but only while
 * relay's own UI is the caller, which {@link RelayLang#isResolvingKeybinds()} tracks. A key probe
 * arrives through a server-sent component, outside that window, and sees exactly what a vanilla
 * client shows: a key that does not resolve. The strings remain absent from {@code storage}, so
 * anything that walks the map directly does not find them either.
 *
 * <p>Both lookup methods are covered because they are the whole probe surface: {@code decompose}
 * resolves text through {@code getOrDefault}, and a fallback-carrying component is decided by
 * {@code has}. Leaving either unguarded would leak the keys through the other.
 */
@Mixin(ClientLanguage.class)
public abstract class ClientLanguageMixin {
    @Inject(method = "getOrDefault(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            at = @At("HEAD"), cancellable = true)
    private void relay$serveKeybindName(String key, String fallback,
                                        CallbackInfoReturnable<String> cir) {
        if (RelayLang.isResolvingKeybinds() && RelayLang.hasKeybind(key)) {
            cir.setReturnValue(RelayLang.keybind(key));
        }
    }

    @Inject(method = "has(Ljava/lang/String;)Z", at = @At("HEAD"), cancellable = true)
    private void relay$hasKeybindName(String key, CallbackInfoReturnable<Boolean> cir) {
        if (RelayLang.isResolvingKeybinds() && RelayLang.hasKeybind(key)) {
            cir.setReturnValue(true);
        }
    }
}
