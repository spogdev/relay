package dev.spog.teamlocator.mixin.client;

import dev.spog.teamlocator.client.RelayLang;
import net.minecraft.client.gui.screen.option.ControlsListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the keybind-name window open while the list rebuilds its rows.
 *
 * <p>A row bakes its name into a widget when it is created rather than reading it each frame, so the
 * window has to cover row building and nothing else. {@link KeyBindsScreenMixin} covers the first
 * build; this covers every rebuild after it, which is what happens when a key is bound or reset.
 *
 * <p>The list's constructor is deliberately not a target: an {@code @At("HEAD")} injection into a
 * constructor runs before {@code super()}, where {@code this} does not exist yet, so Mixin requires
 * such a handler to be static and refuses to apply it otherwise -- which fails the whole mixin and
 * takes the screen down with it. The screen's own body-building method is wrapped instead.
 */
@Mixin(ControlsListWidget.class)
public abstract class KeyBindsListMixin {
    @Inject(method = "update", at = @At("HEAD"))
    private void relay$openWindowForRefresh(CallbackInfo ci) {
        RelayLang.setResolvingKeybinds(true);
    }

    @Inject(method = "update", at = @At("RETURN"))
    private void relay$closeWindowAfterRefresh(CallbackInfo ci) {
        RelayLang.setResolvingKeybinds(false);
    }
}
