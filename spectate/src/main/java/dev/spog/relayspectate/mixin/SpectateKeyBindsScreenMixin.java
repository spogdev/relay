package dev.spog.relayspectate.mixin;

import dev.spog.relayspectate.SpectateLang;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens this addon's keybind-name window for the initial build of the controls screen.
 *
 * <p>A near-copy of Relay's equivalent, and intentionally so: the addon used to rely on Relay
 * opening the window for it, which meant its keybind rows rendered as raw keys without Relay
 * installed. Owning the window costs this duplication and buys independence.
 *
 * <p>Two mods injecting the same methods is fine -- both are {@code @Inject}, neither cancels, and
 * each sets its own flag.
 *
 * <p>{@code addContents} is where the screen constructs its keybind list, so wrapping it is what
 * lets the rows resolve their names. It is targeted in preference to the list's own constructor
 * because an {@code @At("HEAD")} injection into a constructor runs before {@code super()}, where
 * {@code this} does not exist yet; Mixin requires such a handler to be static and refuses to apply
 * otherwise, which would fail the whole mixin and take the screen down with it.
 *
 * <p>The clear in the render pass is the safety net. A RETURN injector does not run when its
 * method throws, so if building the rows failed part-way the window would otherwise stay open for
 * the rest of the session and every later lookup would answer. Clearing here bounds that to one
 * frame, and is correct because the rows are already built by then.
 */
@Mixin(KeyBindsScreen.class)
public abstract class SpectateKeyBindsScreenMixin {
    @Inject(method = "addContents", at = @At("HEAD"))
    private void relayspectate$openWindowForBuild(CallbackInfo ci) {
        SpectateLang.setResolvingKeybinds(true);
    }

    @Inject(method = "addContents", at = @At("RETURN"))
    private void relayspectate$closeWindowAfterBuild(CallbackInfo ci) {
        SpectateLang.setResolvingKeybinds(false);
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void relayspectate$closeWindow(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                           float delta, CallbackInfo ci) {
        SpectateLang.setResolvingKeybinds(false);
    }
}
