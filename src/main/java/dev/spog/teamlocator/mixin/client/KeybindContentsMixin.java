package dev.spog.teamlocator.mixin.client;

import dev.spog.teamlocator.client.RelayLang;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.KeybindContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Closes the keybind-component half of the sign-translation probe (MC-265322).
 *
 * <p>{@link RelayLang} keeps relay's strings out of the translation table, which defeats a
 * {@code {"translate":"relay.config.title"}} probe. A {@code {"keybind":"key.relay.ping"}} probe
 * takes a different route and is not affected by that: it resolves through {@code KeyMapping.ALL},
 * so any <em>registered</em> keybind answers with its bound key's display name. Relay's keybinds are
 * registered -- they have to be, to be bindable -- so this path reported them even with no lang
 * entry anywhere.
 *
 * <p>That display name differs from the key string, which is exactly what a detector tests for: it
 * sends the key and treats "came back unchanged" as absent, anything else as present. So for relay's
 * own keybinds this returns the key string itself, which is what a client without the mod produces,
 * and the probe reads clean.
 *
 * <p>Scoped to relay's seven keys: every other keybind, vanilla or from another mod, resolves
 * normally. The aim is to not advertise relay, not to break {@code keybind} components in general.
 *
 * <p>Note this deliberately ignores {@link RelayLang#isResolvingKeybinds()}. That window exists so
 * the Controls screen can draw names, and the Controls screen does not render keybind components --
 * it reads {@code KeyMapping} directly. Honouring the window here would only widen the leak.
 */
@Mixin(KeybindContents.class)
public abstract class KeybindContentsMixin {
    @Shadow @Final private String name;

    @Inject(method = "getNestedComponent", at = @At("HEAD"), cancellable = true)
    private void relay$hideOwnKeybinds(CallbackInfoReturnable<Component> cir) {
        if (RelayLang.hasKeybind(name)) {
            cir.setReturnValue(Component.literal(name));
        }
    }
}
