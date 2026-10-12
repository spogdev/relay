package dev.spog.relayspectate.mixin;

import dev.spog.relayspectate.SpectateLang;
import dev.spog.teamlocator.client.RelayLang;
import net.minecraft.client.resource.language.TranslationStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Serves this addon's keybind names to the Controls screen without putting them in the translation
 * table.
 *
 * <p>The Controls screen looks keybind names up through {@code TranslationStorage}, so a mod whose
 * strings are private has to answer there or its rows render as raw keys. Answering unconditionally
 * would re-open the probe this addon is avoiding, so the answer is gated on
 * {@link RelayLang#isResolvingKeybinds()} -- Relay's existing window, set only while a screen that
 * legitimately shows keybind names is building its rows.
 *
 * <p>Reusing Relay's window rather than adding a second one is deliberate: Relay already owns the
 * mixins that open and close it around the keybinds screen's row building, and a parallel window
 * here would have to duplicate those injections and keep them in step. The addon declares Relay as
 * a hard dependency, so the window is always present.
 */
@Mixin(TranslationStorage.class)
public abstract class SpectateLanguageMixin {
    @Inject(method = "getOrDefault(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            at = @At("HEAD"), cancellable = true)
    private void relayspectate$serveKeybindName(String key, String fallback,
                                                CallbackInfoReturnable<String> cir) {
        if (RelayLang.isResolvingKeybinds() && SpectateLang.hasKeybind(key)) {
            cir.setReturnValue(SpectateLang.keybind(key));
        }
    }

    @Inject(method = "has(Ljava/lang/String;)Z", at = @At("HEAD"), cancellable = true)
    private void relayspectate$hasKeybindName(String key, CallbackInfoReturnable<Boolean> cir) {
        if (RelayLang.isResolvingKeybinds() && SpectateLang.hasKeybind(key)) {
            cir.setReturnValue(true);
        }
    }
}
