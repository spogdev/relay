package dev.spog.teamlocator.test;

import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.gui.TeamLocatorConfigScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.Locale;

/**
 * Opens the settings screen and visits every tab in a real client.
 *
 * <p>The screen moved onto SporeUI, which means every row is now drawn by the
 * library rather than by this mod. Compiling proves the call sites match; it
 * proves nothing about whether the panel still paints, because all of that
 * happens inside {@code render}, which the compiler checks and never runs.
 *
 * <p>It asserts nothing about the pixels. A screenshot comparison would need
 * regenerating on every deliberate change, and what is worth catching here is
 * a screen that throws or lays out wrongly -- which is what looking at the
 * screenshots catches.
 */
public class ConfigScreenGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        // Built on the client thread: the constructor reaches
        // MinecraftClient.getInstance() for the font its text boxes measure
        // with, which throws off it.
        TeamLocatorConfigScreen screen = context.computeOnClient(client ->
                new TeamLocatorConfigScreen(null, TeamLocatorClient.CONFIG));
        context.setScreen(() -> screen);
        context.waitForScreen(TeamLocatorConfigScreen.class);

        // GUI scale 1, so the panel is measured at the size a player on a
        // normal window sees. The harness runs an 854x480 window, which at
        // the default scale of 2 is only 427x240 logical -- short enough that
        // every tab overflows and the test would be screenshotting a
        // scrollbar rather than the layout.
        context.runOnClient(client -> {
            client.options.guiScale().set(1);
            client.resizeGui();
        });
        context.waitTicks(5);

        for (TeamLocatorConfigScreen.Tab tab : TeamLocatorConfigScreen.Tab.values()) {
            context.runOnClient(client -> screen.openTabForTest(tab));
            context.waitTicks(5);
            context.takeScreenshot("relay-" + tab.name().toLowerCase(Locale.ROOT));
        }

        // The harness requires the client to end on the title screen, so one
        // test cannot leave a screen open for the next to trip over.
        context.setScreen(TitleScreen::new);
        context.waitForScreen(TitleScreen.class);
    }
}
