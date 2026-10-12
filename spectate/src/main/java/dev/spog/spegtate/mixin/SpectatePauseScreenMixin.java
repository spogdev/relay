package dev.spog.spegtate.mixin;

import dev.spog.spegtate.SpectateLang;
import dev.spog.spegtate.SpectateManager;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.GameMenuScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Relabels the pause menu's disconnect button while spectating.
 *
 * <p>"Disconnect" is wrong during a session and misleading about what it will do: the player is not
 * connected to the world they are looking at, and the button ends their view of someone else's
 * world rather than leaving their own. {@code SpectateDisconnectMixin} makes it end the session;
 * this says so.
 *
 * <p>The label is set rather than the button replaced, because {@code ButtonWidget.onPress} is final and
 * the vanilla handler is a lambda. The text is the only part that needs changing -- the behaviour
 * is corrected at {@code MinecraftClient.disconnectFromWorld}, which is where every route out of a world
 * ends up.
 */
@Mixin(GameMenuScreen.class)
public abstract class SpectatePauseScreenMixin {
    @Shadow
    private ButtonWidget exitButton;

    @Inject(method = "init", at = @At("RETURN"))
    private void spegtate$relabel(CallbackInfo ci) {
        if (exitButton != null && SpectateManager.get().isViewing()) {
            // SpectateLang.text, not Text.translatable: this mod's UI strings are not
            // registered with the game's language system, so a translatable component would render
            // as its raw key. The key already exists for the spectate menu's own stop button.
            exitButton.setMessage(SpectateLang.text("menu.stop"));
        }
    }
}
