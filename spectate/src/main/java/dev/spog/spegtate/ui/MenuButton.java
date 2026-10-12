package dev.spog.spegtate.ui;

import dev.spog.spegtate.SpectateLang;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * Adds a Spectate button to the multiplayer screen.
 *
 * <p>This exists because a keybind cannot reach this feature. Minecraft only dispatches keybinds
 * while a world is loaded, and a spectate session can only <em>start</em> from the menu -- it
 * replaces the client's world, so starting one in-game would disconnect the player from the server
 * they are playing on. Those two facts together left the original keybind-only entry point
 * unreachable: unusable from the menu because keybinds do not fire there, and refused in-game
 * because that is not a valid place to start.
 *
 * <p>The multiplayer screen is the right home for it. It is where a player goes to reach other
 * people's servers, which is what spectating does without joining one, and it is reachable from
 * the title screen in one click.
 *
 * <p>The keybind is kept, but only usefully stops a session: that does happen in a world -- the
 * target's -- so it dispatches normally.
 */
public final class MenuButton {
    /** Matches the vanilla buttons on that screen. */
    private static final int WIDTH = 100;
    private static final int HEIGHT = 20;
    private static final int MARGIN = 4;

    private MenuButton() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof JoinMultiplayerScreen) {
                addTo(screen, scaledWidth, scaledHeight);
            }
        });
    }

    /**
     * Places the button in the bottom-right corner.
     *
     * <p>Positioned absolutely rather than inserted into the screen's layout: the multiplayer
     * screen arranges its buttons in a fixed grid that assumes a known set, and adding to it
     * shifts every vanilla button. A corner that the screen does not use avoids touching that.
     *
     * <p>The corner is derived from the screen size rather than fixed, so it follows the
     * window and the GUI scale instead of drifting at other sizes.
     */
    private static void addTo(Screen screen, int scaledWidth, int scaledHeight) {
        java.util.List<AbstractWidget> widgets = Screens.getWidgets(screen);
        int x = scaledWidth - WIDTH - MARGIN;
        int y = scaledHeight - HEIGHT - MARGIN;

        // AFTER_INIT can fire more than once for the same screen -- resizing was the
        // case that showed it, stacking a new button on every event until the corner
        // was a wall of them. Vanilla's own re-init is guarded, so the repeat comes
        // from elsewhere in the pack; reusing what is already there is independent of
        // which path fired.
        AbstractWidget existing = find(widgets);
        if (existing != null) {
            // Moved rather than left alone: if the screen kept its widgets through a
            // resize, the old button still sits in what used to be the corner.
            existing.setPosition(x, y);
            return;
        }
        Button button = Button.builder(
                        SpectateLang.text("menu.title"),
                        b -> Screens.getMinecraft(screen).setScreenAndShow(new SpectateScreen(screen)))
                .bounds(x, y, WIDTH, HEIGHT)
                .build();
        widgets.add(button);
    }

    /**
     * This screen's existing spectate button, or null.
     *
     * <p>Identified by its label: the list holds the screen's vanilla widgets too, and this is
     * the only one carrying that text.
     */
    private static AbstractWidget find(java.util.List<AbstractWidget> widgets) {
        String label = SpectateLang.text("menu.title").getString();
        for (AbstractWidget widget : widgets) {
            if (widget.getMessage() != null && label.equals(widget.getMessage().getString())) {
                return widget;
            }
        }
        return null;
    }
}
