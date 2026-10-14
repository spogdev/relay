package dev.spog.spegtate.ui;

import dev.spog.spegtate.SpectateLang;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Tells someone the service refused them, because this mod is private.
 *
 * <p>Shown once per launch, the first time the service answers "not whitelisted". Not a toast:
 * the message asks the reader to go and do something (DM someone on Discord), and a toast that
 * fades after a few seconds is a poor way to deliver an address. It is centred and modal for the
 * same reason -- there is nothing else the mod can usefully do for this account, so there is
 * nothing to let them get back to.
 *
 * <p>Dismissible rather than permanent. The refusal does not stop the game, only this mod, and
 * trapping someone on a screen they cannot leave over an optional addon would be hostile.
 *
 * <p>The Discord handle is a constant rather than a lookup.
 * {@code https://dua.spog.dev/api/v1/discord/} resolves a <em>Minecraft UUID</em> to a linked
 * Discord account; it has no route that takes a Discord id, and rejects one with "malformed
 * uuid". Resolving the owner's own id would therefore mean either hardcoding the Minecraft UUID
 * it hangs off -- no better than hardcoding the handle, but with a network call that can fail at
 * the moment the message is needed -- or adding a reverse route to that service. Until such a
 * route exists this is the honest version: one constant, in one place.
 */
public final class AccessDeniedScreen extends Screen {
    /**
     * The Discord account to contact, id {@code 809088123430633532}.
     *
     * <p>If this handle changes, change it here. See the class javadoc for why it is not
     * resolved at runtime.
     */
    private static final String CONTACT = "spogdev";

    private static final int TITLE_COLOR = 0xFFFF5555;
    private static final int BODY_COLOR = 0xFFAAAAAA;
    private static final int BUTTON_WIDTH = 120;
    private static final int BUTTON_HEIGHT = 20;

    /** Vertical gap between the lines of the message. */
    private static final int LINE_GAP = 12;

    /**
     * Shown at most once per launch.
     *
     * <p>The client retries authentication on a timer and the service refuses every attempt, so
     * without this the screen would reappear every minute for as long as the game is open.
     */
    private static boolean shown;

    private final Screen parent;

    public AccessDeniedScreen(Screen parent) {
        super(SpectateLang.text("access.title"));
        this.parent = parent;
    }

    /** Whether this screen has already been shown this launch. */
    public static boolean alreadyShown() {
        return shown;
    }

    public static void markShown() {
        shown = true;
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(
                SpectateLang.text("access.dismiss"),
                b -> close()).dimensions(width / 2 - BUTTON_WIDTH / 2,
                        height / 2 + LINE_GAP * 3, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int centre = width / 2;
        int y = height / 2 - LINE_GAP * 3;

        context.drawCenteredTextWithShadow(textRenderer,
                SpectateLang.text("access.title"), centre, y, TITLE_COLOR);
        y += LINE_GAP * 2;
        context.drawCenteredTextWithShadow(textRenderer,
                SpectateLang.text("access.line1"), centre, y, BODY_COLOR);
        y += LINE_GAP;
        context.drawCenteredTextWithShadow(textRenderer,
                SpectateLang.text("access.line2", CONTACT), centre, y, BODY_COLOR);
    }

    /**
     * Closing returns to whatever was open before, normally the title screen.
     *
     * <p>Never into the world: this screen can appear at the main menu, where there is no world
     * to return to, and {@code null} there would drop the player into a blank screen.
     */
    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    /**
     * Escape dismisses it, like any other informational screen.
     *
     * <p>True rather than false: this is not a confirmation and nothing is lost by closing it.
     */
    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
