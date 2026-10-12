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
        Button button = Button.builder(
                        SpectateLang.text("menu.title"),
                        b -> Screens.getMinecraft(screen).setScreenAndShow(new SpectateScreen(screen)))
                .bounds(scaledWidth - WIDTH - MARGIN, scaledHeight - HEIGHT - MARGIN,
                        WIDTH, HEIGHT)
                .build();
        java.util.List<AbstractWidget> widgets = Screens.getWidgets(screen);
        widgets.add(button);
    }
}
