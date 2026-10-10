package dev.spog.teamlocator.mixin.client;

import dev.spog.teamlocator.client.RelayLang;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Closes the keybind-name window, unconditionally, once per frame.
 *
 * <p>This is the safety net for {@link KeyBindsListMixin}: the list opens the window only around
 * building and refreshing its entries, but a throw part-way through either would skip the RETURN
 * injector that closes it. Clearing here means the widest the window can ever stay open is the
 * remainder of one frame, instead of the rest of the session.
 *
 * <p>Clearing at the head of the render pass is correct because by then the entries are already
 * built: the names were resolved during construction, so nothing in the render pass needs the
 * window open.
 */
@Mixin(KeyBindsScreen.class)
public abstract class KeyBindsScreenMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void relay$closeWindow(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float delta, CallbackInfo ci) {
        RelayLang.setResolvingKeybinds(false);
    }
}
