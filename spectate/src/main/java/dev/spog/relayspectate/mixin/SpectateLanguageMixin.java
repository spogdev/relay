package dev.spog.relayspectate.mixin;

import dev.spog.relayspectate.SpectateLang;
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
 * {@link SpectateLang#isResolvingKeybinds()} -- set only while a screen that legitimately shows
 * keybind names is building its rows.
 *
 * <p>The window is this addon's own, opened by {@link SpectateKeyBindsScreenMixin} and
 * {@link SpectateKeyBindsListMixin}. It used to be Relay's, shared to avoid duplicating two small
 * injections; that made Relay a hard dependency for the sake of a boolean, and without it these
 * rows rendered as raw keys.
 */
@Mixin(TranslationStorage.class)
public abstract class SpectateLanguageMixin {
    @Inject(method = "get(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            at = @At("HEAD"), cancellable = true)
    private void relayspectate$serveKeybindName(String key, String fallback,
                                                CallbackInfoReturnable<String> cir) {
        if (SpectateLang.isResolvingKeybinds() && SpectateLang.hasKeybind(key)) {
            cir.setReturnValue(SpectateLang.keybind(key));
        }
    }

    @Inject(method = "hasTranslation(Ljava/lang/String;)Z", at = @At("HEAD"), cancellable = true)
    private void relayspectate$hasKeybindName(String key, CallbackInfoReturnable<Boolean> cir) {
        if (SpectateLang.isResolvingKeybinds() && SpectateLang.hasKeybind(key)) {
            cir.setReturnValue(true);
        }
    }
}
