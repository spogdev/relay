package dev.spog.teamlocator.mixin.client;

import dev.spog.teamlocator.client.RelayLang;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens the window in which relay's keybind names resolve.
 *
 * <p>Scoped to this list rather than to the screen as a whole because the names are baked into
 * widgets when an entry is built, not read each frame: the window has to cover construction and
 * refresh, and nothing else. Outside it a lookup of {@code key.relay.*} fails exactly as it would on
 * a vanilla client, which is what makes the keys unprobeable.
 *
 * <p>A RETURN injector does not run when the method throws, so the flag is not closed by pairing
 * alone. It is instead re-armed on every open and cleared by the screen's own render pass
 * ({@link KeyBindsScreenMixin}), so a failure part-way through building the list cannot leave it
 * standing for the rest of the session -- which would make every later lookup answer.
 */
@Mixin(KeyBindsList.class)
public abstract class KeyBindsListMixin {
    @Inject(method = "<init>", at = @At("HEAD"))
    private void relay$openWindowForBuild(net.minecraft.client.gui.screens.options.controls.KeyBindsScreen screen,
                                          net.minecraft.client.Minecraft minecraft, CallbackInfo ci) {
        RelayLang.setResolvingKeybinds(true);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void relay$closeWindowAfterBuild(net.minecraft.client.gui.screens.options.controls.KeyBindsScreen screen,
                                             net.minecraft.client.Minecraft minecraft, CallbackInfo ci) {
        RelayLang.setResolvingKeybinds(false);
    }

    @Inject(method = "refreshEntries", at = @At("HEAD"))
    private void relay$openWindowForRefresh(CallbackInfo ci) {
        RelayLang.setResolvingKeybinds(true);
    }

    @Inject(method = "refreshEntries", at = @At("RETURN"))
    private void relay$closeWindowAfterRefresh(CallbackInfo ci) {
        RelayLang.setResolvingKeybinds(false);
    }
}
