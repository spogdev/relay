package dev.spog.teamlocator.test;

import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.config.TrustEntry;
import dev.spog.teamlocator.client.gui.TeamLocatorConfigScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.Locale;
import java.util.UUID;

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
        // Two trust entries, so the Trust tab draws rows rather than its empty-list line. One is
        // muted and hidden and one is left at the defaults, so both switch states and the remove
        // X are in the screenshot. Set in memory only; nothing is saved.
        context.runOnClient(client -> {
            var trusted = TeamLocatorClient.CONFIG.global.trusted;
            trusted.clear();
            TrustEntry muted = new TrustEntry(
                    UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "Notch");
            muted.mutePings = true;
            muted.hidden = true;
            trusted.add(muted);
            trusted.add(new TrustEntry(
                    UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6"), "jeb_"));
        });

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
        // A muted player is hidden everywhere, so leaving Notch muted would hide him from the tests
        // that follow.
        context.runOnClient(client -> TeamLocatorClient.CONFIG.global.trusted.clear());
    }
}
