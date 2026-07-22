package dev.spog.teamlocator.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.spog.teamlocator.client.compat.xaero.XaeroCompat;
import dev.spog.teamlocator.client.compat.xaero.XaeroWaypoints;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.util.Formatting;
import net.minecraft.text.Text;

/**
 * {@code /relaywaypoint <name> <x> <y> <z> [dimension]} — adds a waypoint a teammate shared.
 *
 * <p>A <b>client</b> command: it is intercepted locally and never reaches the Minecraft server, so
 * accepting a shared location does not broadcast those coordinates to everyone on the server. That
 * is also why the chat prompt uses a suggest-click rather than a run-click — vanilla's RunCommand
 * hands the text straight to the server, which has never heard of this command.
 *
 * <p>Nothing is added without the user running it. Waypoint sharing is one-way, so anyone who has
 * added you can offer you a location; letting that write into your map unprompted would make it a
 * spam vector.
 */
@Environment(EnvType.CLIENT)
public final class RelayWaypointCommand {
    private RelayWaypointCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("relaywaypoint")
                        .then(ClientCommands.argument("name", StringArgumentType.string())
                                .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                                .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> {
                                                            add(ctx.getSource(),
                                                                    StringArgumentType.getString(ctx, "name"),
                                                                    IntegerArgumentType.getInteger(ctx, "x"),
                                                                    IntegerArgumentType.getInteger(ctx, "y"),
                                                                    IntegerArgumentType.getInteger(ctx, "z"));
                                                            return 1;
                                                        })
                                                        .then(ClientCommands.argument("dimension", StringArgumentType.greedyString())
                                                                .executes(ctx -> {
                                                                    add(ctx.getSource(),
                                                                            StringArgumentType.getString(ctx, "name"),
                                                                            IntegerArgumentType.getInteger(ctx, "x"),
                                                                            IntegerArgumentType.getInteger(ctx, "y"),
                                                                            IntegerArgumentType.getInteger(ctx, "z"));
                                                                    return 1;
                                                                }))))))));
    }

    private static void add(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source,
                            String name, int x, int y, int z) {
        if (!XaeroCompat.isMinimapInstalled()) {
            source.sendError(Text.literal(
                    "Xaero's Minimap isn't installed, so there's nowhere to add the waypoint."));
            return;
        }
        if (XaeroWaypoints.add(name, x, y, z)) {
            RelayChat.send(Text.literal("Added waypoint: ")
                    .formatted(Formatting.GREEN)
                    .append(Text.literal(name).formatted(Formatting.WHITE)));
        } else {
            source.sendError(Text.literal(
                    "Couldn't add the waypoint — the minimap has no active world yet."));
        }
    }
}
