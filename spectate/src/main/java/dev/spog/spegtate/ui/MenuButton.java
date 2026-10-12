package dev.spog.spegtate.ui;

import dev.spog.spegtate.SpectateLang;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.Screen;

/**
 * Adds a Spectate button to the multiplayer screen.
 *
 * <p>This exists because a keybind cannot reach this feature. MinecraftClient only dispatches keybinds
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
            if (screen instanceof MultiplayerScreen) {
                addTo(screen, scaledHeight);
            }
        });
    }

    /**
     * Places the button in the top-left, clear of the vanilla row along the bottom.
     *
     * <p>Positioned absolutely rather than inserted into the screen's layout: the multiplayer
     * screen arranges its buttons in a fixed grid that assumes a known set, and adding to it
     * shifts every vanilla button. A corner that the screen does not use avoids touching that.
     */
    private static void addTo(Screen screen, int scaledHeight) {
        ButtonWidget button = ButtonWidget.builder(
                        SpectateLang.text("menu.title"),
                        b -> Screens.getClient(screen).setScreen(new SpectateScreen(screen)))
                .dimensions(MARGIN, MARGIN, WIDTH, HEIGHT)
                .build();
        java.util.List<ClickableWidget> widgets = Screens.getButtons(screen);
        widgets.add(button);
    }
}
