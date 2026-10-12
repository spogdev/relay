package dev.spog.spegtate.mixin;

import dev.spog.spegtate.SpectateLang;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.KeybindContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Closes the keybind-component probe path for this addon's keybinds (MC-265322).
 *
 * <p>Mirrors Relay's own {@code KeybindContentsMixin}, and is needed for the same reason. A
 * {@code {"keybind":"key.spegtate.open_menu"}} component resolves through
 * {@code KeyMapping.ALL}, so any <em>registered</em> keybind answers with its bound key's display
 * name -- independent of the translation table, which is why {@link SpectateLang} keeping strings
 * private is not sufficient on its own. Keybinds have to be registered to be bindable, so the
 * registration cannot be avoided; only the answer can.
 *
 * <p>A detector sends the key and treats "came back unchanged" as absent, anything else as present.
 * So for this addon's keys the key string itself is returned, which is what a client without the
 * addon produces, and the probe reads clean.
 *
 * <p>Scoped to this addon's three keys. Every other keybind -- vanilla, Relay's, another mod's --
 * resolves normally; the aim is to not advertise the addon, not to break {@code keybind} components.
 */
@Mixin(KeybindContents.class)
public abstract class SpectateKeybindMixin {
    @Shadow @Final private String name;

    @Inject(method = "getNestedComponent", at = @At("HEAD"), cancellable = true)
    private void spegtate$hideOwnKeybinds(CallbackInfoReturnable<Component> cir) {
        if (SpectateLang.hasKeybind(name)) {
            cir.setReturnValue(Component.literal(name));
        }
    }
}
