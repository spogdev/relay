package dev.spog.teamlocator.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.text.Text;

import java.util.function.Consumer;

/**
 * A flat button drawn in the profile panel's own style, so it sits with the
 * cards instead of looking like a stock Minecraft widget.
 */
public class PanelButton extends PressableWidget {
	private static final int FILL = 0x60161B22;
	private static final int FILL_HOVERED = 0x8022303F;
	private static final int BORDER = 0x70323B47;
	private static final int BORDER_HOVERED = 0xA05B6B7D;
	private static final int FILL_DISABLED = 0x40101319;
	private static final int TEXT = 0xFFE4EAF2;
	private static final int TEXT_DISABLED = 0xFF6C7683;

	private final Consumer<PanelButton> onPress;

	public PanelButton(int x, int y, int width, int height, Text message,
			Consumer<PanelButton> onPress) {
		super(x, y, width, height, message);
		this.onPress = onPress;
	}

	@Override
	public void onPress(AbstractInput input) {
		onPress.accept(this);
	}

	/**
	 * PressableWidget paints the vanilla button texture in a final
	 * renderWidget, so the panel styling goes here instead -- this is the hook
	 * it leaves open for subclasses.
	 */
	@Override
	protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {
		int left = getX();
		int top = getY();
		int right = left + width;
		int bottom = top + height;

		boolean hovered = isHovered() && active;
		int fill = !active ? FILL_DISABLED : (hovered ? FILL_HOVERED : FILL);
		int border = hovered ? BORDER_HOVERED : BORDER;

		context.fill(left, top, right, bottom, fill);
		context.fill(left, top, right, top + 1, border);
		context.fill(left, bottom - 1, right, bottom, border);
		context.fill(left, top, left + 1, bottom, border);
		context.fill(right - 1, top, right, bottom, border);

		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		context.drawTextWithShadow(textRenderer, getMessage(),
				left + (width - textRenderer.getWidth(getMessage())) / 2,
				top + (height - textRenderer.fontHeight) / 2,
				active ? TEXT : TEXT_DISABLED);
	}

	@Override
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		appendDefaultNarrations(builder);
	}
}
