package dev.spog.teamlocator.mixin.client;

import dev.spog.teamlocator.client.RelayLang;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens the keybind-name window for the initial build of the screen, and closes it each frame.
 *
 * <p>{@code addContents} is where the screen constructs its keybind list, so wrapping it is what
 * lets the rows resolve their names. It is targeted in preference to the list's own constructor
 * because an {@code @At("HEAD")} injection into a constructor runs before {@code super()}, where
 * {@code this} does not exist yet; Mixin requires such a handler to be static and refuses to apply
 * otherwise, which previously took down the whole mixin and the screen with it.
 *
 * <p>The clear in the render pass is the safety net. A RETURN injector does not run when its method
 * throws, so if building the rows failed part-way the window would otherwise stay open for the rest
 * of the session and every later lookup would answer. Clearing here bounds that to one frame.
 *
 * <p>Clearing at the head of the render pass is correct because the rows are already built by then:
 * their names were resolved during {@code addContents}, so nothing in the render pass needs the
 * window open.
 */
@Mixin(KeyBindsScreen.class)
public abstract class KeyBindsScreenMixin {
    @Inject(method = "addContents", at = @At("HEAD"))
    private void relay$openWindowForBuild(CallbackInfo ci) {
        RelayLang.setResolvingKeybinds(true);
    }

    @Inject(method = "addContents", at = @At("RETURN"))
    private void relay$closeWindowAfterBuild(CallbackInfo ci) {
        RelayLang.setResolvingKeybinds(false);
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void relay$closeWindow(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float delta, CallbackInfo ci) {
        RelayLang.setResolvingKeybinds(false);
    }
}
