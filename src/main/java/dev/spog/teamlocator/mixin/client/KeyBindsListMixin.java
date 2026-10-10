package dev.spog.teamlocator.mixin.client;

import dev.spog.teamlocator.client.RelayLang;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
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
 * <p>{@code refreshEntries} is the target because it is the one rebuild entry point present on every
 * version relay supports. The screen-level {@code refreshKeybindLabels} exists only on 26.3, and
 * injecting into a method that is absent fails the whole mixin under {@code defaultRequire}.
 */
@Mixin(KeyBindsList.class)
public abstract class KeyBindsListMixin {
    @Inject(method = "refreshEntries", at = @At("HEAD"))
    private void relay$openWindowForRefresh(CallbackInfo ci) {
        RelayLang.setResolvingKeybinds(true);
    }

    @Inject(method = "refreshEntries", at = @At("RETURN"))
    private void relay$closeWindowAfterRefresh(CallbackInfo ci) {
        RelayLang.setResolvingKeybinds(false);
    }
}
