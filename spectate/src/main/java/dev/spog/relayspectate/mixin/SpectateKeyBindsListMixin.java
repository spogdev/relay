package dev.spog.relayspectate.mixin;

import dev.spog.relayspectate.SpectateLang;
import net.minecraft.client.gui.screen.option.ControlsListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps this addon's keybind-name window open while the list rebuilds its rows.
 *
 * <p>A row bakes its name into a widget when it is created rather than reading it each frame, so
 * the window has to cover row building and nothing else. {@link SpectateKeyBindsScreenMixin}
 * covers the first build; this covers every rebuild after it, which is what happens when a key is
 * bound or reset.
 *
 * <p>{@code update} is this version's rebuild entry point -- the same method Relay's own
 * equivalent targets on this branch, which is where the name was taken from rather than guessed.
 */
@Mixin(ControlsListWidget.class)
public abstract class SpectateKeyBindsListMixin {
    @Inject(method = "update", at = @At("HEAD"))
    private void relayspectate$openWindowForRefresh(CallbackInfo ci) {
        SpectateLang.setResolvingKeybinds(true);
    }

    @Inject(method = "update", at = @At("RETURN"))
    private void relayspectate$closeWindowAfterRefresh(CallbackInfo ci) {
        SpectateLang.setResolvingKeybinds(false);
    }
}
