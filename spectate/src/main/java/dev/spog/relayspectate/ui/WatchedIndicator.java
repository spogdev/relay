package dev.spog.relayspectate.ui;

import dev.spog.relayspectate.SpectateClient;
import dev.spog.relayspectate.SpectateConfig;
import dev.spog.relayspectate.SpectateLang;
import dev.spog.relayspectate.SpectateManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Identifier;

/**
 * Tells this player when someone is watching them.
 *
 * <p>Not decoration. Sessions start on standing trust with no prompt, so this is the only signal a
 * player gets that a teammate is seeing what they see. Without it the feature would be
 * indistinguishable from silent surveillance, which is not what trusting someone with your
 * coordinates implies.
 *
 * <p>Drawn top-centre, which is deliberately intrusive: the corner is where a notice goes to be
 * missed. It is suppressed only when the HUD is hidden entirely ({@code F1}), since a player taking
 * a screenshot has already chosen to hide overlays.
 */
@Environment(EnvType.CLIENT)
public final class WatchedIndicator implements HudElement {
    public static final Identifier ID =
            Identifier.of(SpectateClient.MOD_ID, "watched");

    private static final int COLOR = 0xFFFFAA00;
    private static final int TOP_MARGIN = 4;

    @Override
    public void extractRenderState(DrawContext graphics, RenderTickCounter delta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hideGui || !SpectateConfig.get().showWatchedIndicator) {
            return;
        }
        SpectateManager manager = SpectateManager.get();
        if (!manager.isBeingWatched()) {
            return;
        }
        int count = manager.viewerCount();
        String text = count == 1
                ? SpectateLang.string("status.watched", "A teammate")
                : SpectateLang.string("status.watched_many", count);
        int x = (graphics.guiWidth() - mc.font.width(text)) / 2;
        graphics.text(mc.font, text, x, TOP_MARGIN, COLOR, true);
    }
}
