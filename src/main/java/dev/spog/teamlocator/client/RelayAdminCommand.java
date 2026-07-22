package dev.spog.teamlocator.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.util.Formatting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Relay moderation for administrators:
 * {@code /relay <test|blacklist|whitelist> <player>}, {@code /relay stats}, and
 * {@code /relay pingcolor <player> <#rrggbb|-remove>}.
 *
 * <p>The command is registered for <em>every</em> client, not just admins: the client has no way to
 * know whether this account is an administrator, and deciding locally would be meaningless anyway.
 * Authorization is entirely relay-side, against the Mojang-verified UUID of the sending session, so
 * a non-admin running this simply gets "You do not have permission" back from the relay. Nothing
 * here grants anything.
 */
@Environment(EnvType.CLIENT)
public final class RelayAdminCommand {
    private RelayAdminCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommandManager.literal("relay")
                        // Brigadier hides a node whose requirement fails: for a non-admin the whole
                        // /relay tree is absent from autocomplete and unexecutable, so the command
                        // does not appear to exist. Cosmetic only — the relay re-checks every
                        // command against the verified UUID regardless of what the client believes.
                        //
                        // This is evaluated once, when the command tree is built on world join,
                        // which happens BEFORE the relay finishes authenticating. So it cannot ask
                        // the live connection (always false that early, hiding the command from
                        // real admins); it reads the answer remembered from the last connect, and
                        // also accepts the live flag for the case where the tree is rebuilt later.
                        .requires(source -> TeamLocatorClient.CONFIG.wasRelayAdmin
                                || TeamLocatorClient.RELAY.isRelayAdmin())
                        .then(sub("test"))
                        .then(sub("blacklist"))
                        .then(sub("whitelist"))
                        .then(ClientCommandManager.literal("stats").executes(ctx -> {
                            run(ctx.getSource(), "stats", List.of());
                            return 1;
                        }))
                        // pingcolor takes a second argument. greedyString rather than word so a
                        // colour can be pasted with surrounding spaces without the parse failing;
                        // the relay trims and validates it, and answers with the usage line when it
                        // isn't a well-formed #rrggbb or -remove.
                        .then(ClientCommandManager.literal("pingcolor").then(
                                ClientCommandManager.argument("player", StringArgumentType.word()).then(
                                        ClientCommandManager.argument("color", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    run(ctx.getSource(), "pingcolor", List.of(
                                                            StringArgumentType.getString(ctx, "player"),
                                                            StringArgumentType.getString(ctx, "color")));
                                                    return 1;
                                                }))))
                        .executes(ctx -> {
                            ctx.getSource().sendError(
                                    Text.translatable("relay.command.usage"));
                            return 0;
                        })));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource>
            sub(String name) {
        return ClientCommandManager.literal(name).then(
                ClientCommandManager.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            run(ctx.getSource(), name,
                                    List.of(StringArgumentType.getString(ctx, "player")));
                            return 1;
                        }));
    }

    private static void run(FabricClientCommandSource source, String command, List<String> args) {
        if (!TeamLocatorClient.RELAY.isReady()) {
            source.sendError(Text.translatable("relay.command.not_connected"));
            return;
        }
        MinecraftClient mc = source.getClient();
        TeamLocatorClient.RELAY.sendAdminCommand(command, args, (lines, error) ->
                mc.execute(() -> report(lines, error)));
    }

    /** Runs on the game thread. {@code lines} null means the relay never answered. */
    private static void report(List<String> lines, boolean error) {
        if (lines == null) {
            // An older relay has no admin support and ignores the frame outright, so a timeout is
            // indistinguishable from one that is merely slow; say so rather than guessing.
            RelayChat.send(Text.literal("No response from the relay (it may not support admin "
                    + "commands).").formatted(Formatting.RED));
            return;
        }
        for (String line : lines) {
            RelayChat.send(error
                    ? Text.literal(line).formatted(Formatting.RED)
                    : Text.literal(line));
        }
    }
}
