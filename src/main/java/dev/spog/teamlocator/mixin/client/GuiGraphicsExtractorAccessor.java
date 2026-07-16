package dev.spog.teamlocator.mixin.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the render state behind {@link GuiGraphicsExtractor} so the HUD can submit item icons.
 *
 * <p>Items are the one thing the extractor has no public method for — it can draw text, sprites and
 * fills, but an item icon has to go through {@code GuiRenderState.addItem}, and the state itself is
 * private. Vanilla's own inventory takes exactly this path; this accessor just makes it reachable.
 */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsExtractorAccessor {
    @Accessor("guiRenderState")
    GuiRenderState relay$guiRenderState();
}
