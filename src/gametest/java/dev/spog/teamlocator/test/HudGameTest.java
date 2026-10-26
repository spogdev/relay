package dev.spog.teamlocator.test;

import dev.spog.teamlocator.client.ArmorPiece;
import dev.spog.teamlocator.client.ClientState;
import dev.spog.teamlocator.client.TeamLocatorClient;
import dev.spog.teamlocator.client.TrackedPos;
import dev.spog.teamlocator.client.config.TeamConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.UUID;

/**
 * Draws the teammate HUD in a real world, with two made-up teammates, in the layouts and with the
 * options most likely to lay it out differently.
 *
 * <p>The HUD only draws inside a world and only for teammates the relay has reported, so nothing
 * else in the test suite ever paints it. The teammates are fed straight into {@link ClientState},
 * where the relay's updates would land. Like the settings screen test, it asserts nothing about
 * the pixels; the screenshots are what to look at.
 */
public class HudGameTest implements FabricClientGameTest {
    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID JEB = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            context.waitTicks(20);

            // Everything the row can show, set in memory only. Table first: it lays each row out
            // by hand rather than as one run of text, and is where coordinates being off once
            // left a row with nothing after the name.
            context.runOnClient(client -> {
                TeamConfig config = TeamLocatorClient.CONFIG;
                config.hudEnabled = true;
                config.hudShowHealth = true;
                config.hudArmorDisplay = TeamConfig.ArmorDisplay.ALL;
                config.hudAlign = TeamConfig.HudAlign.TABLE;
                config.hudShowCoords = true;
            });
            shoot(context, "hud-table-coords");

            context.runOnClient(client -> TeamLocatorClient.CONFIG.hudShowCoords = false);
            shoot(context, "hud-table-no-coords");

            context.runOnClient(client -> TeamLocatorClient.CONFIG.hudAlign = TeamConfig.HudAlign.LEFT);
            shoot(context, "hud-left-no-coords");

            context.runOnClient(client -> ClientState.reset());
        }
    }

    /**
     * Reports both teammates and screenshots the HUD. Reported again before every shot because a
     * position expires after a few seconds without an update, as it would if the relay went quiet.
     */
    private static void shoot(ClientGameTestContext context, String name) {
        context.runOnClient(client -> {
            List<ArmorPiece> armor = List.of(
                    new ArmorPiece("head", "minecraft:diamond_helmet", 40, 363),
                    new ArmorPiece("chest", "minecraft:diamond_chestplate", 300, 528),
                    new ArmorPiece("legs", "minecraft:iron_leggings", 0, 225),
                    new ArmorPiece("feet", "minecraft:iron_boots", 150, 195));
            Identifier overworld = Identifier.withDefaultNamespace("overworld");
            Identifier nether = Identifier.withDefaultNamespace("the_nether");
            ClientState.updatePosition(new TrackedPos(NOTCH, 1204, 64, -3388, overworld,
                    armor, 14.0f, "#2654e3"));
            ClientState.updatePosition(new TrackedPos(JEB, -88, 31, 512, nether,
                    List.of(), 20.0f, null));
        });
        context.waitTicks(10);
        context.takeScreenshot(name);
    }
}
