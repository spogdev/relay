package dev.spog.spegtate.ui;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.spog.spegtate.SpectateConfig;
import dev.spog.spegtate.SpectateLang;
import dev.spog.spegtate.SpectateManager;
import dev.spog.spegtate.SpectateRoster;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /spegtate} — the addon's administration commands.
 *
 * <p><b>A client command: it never reaches the Minecraft server.</b> Registered through Fabric's
 * client command API, which dispatches locally and does not forward, so the server a player is
 * on sees nothing — no command, no chat, not even a failed one. That matters beyond tidiness:
 * typing a command the server does not know would otherwise produce a visible error there and
 * advertise that this client is running something.
 *
 * <p><b>Hidden from non-admins.</b> Brigadier omits a node whose {@code requires} fails, so for
 * everyone else the whole tree is absent from autocomplete and unexecutable — the command does
 * not appear to exist. That is presentation only: the service re-checks the caller's
 * Mojang-verified UUID on every command, so a patched client that forces the node visible still
 * gets "You do not have permission".
 *
 * <p>Admin status is learned from the service and remembered in the config, because the command
 * tree is built when the client starts — before the service has authenticated — so asking the
 * live connection would hide the command from real admins for the first few seconds of every
 * session.
 */
@Environment(EnvType.CLIENT)
public final class SpegtateCommand {
    /** Players listed per page by {@code playerlist} before it starts paging. */
    private static final int PAGE_SIZE = 25;

    /** The most recent player list, kept so a page link can re-render it without asking again. */
    private static volatile List<String> lastPlayerList = List.of();

    private SpegtateCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommandManager.literal("spegtate")
                        .requires(source -> SpectateConfig.get().wasSpectateAdmin
                                || SpectateManager.get().isSpectateAdmin())
                        .then(whitelist())
                        .then(ClientCommandManager.literal("stats").executes(ctx -> {
                            run(ctx.getSource(), "stats", List.of());
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("playerlist")
                                .executes(ctx -> {
                                    runPlayerList(ctx.getSource(), 1);
                                    return 1;
                                })
                                // Hidden from completion but reachable, so the page links below
                                // can run it; nobody needs to type a page number by hand.
                                .then(ClientCommandManager.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> {
                                            runPlayerList(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "page"));
                                            return 1;
                                        })))
                        .executes(ctx -> {
                            SpectateChat.error(SpectateLang.text("admin.usage"));
                            return 0;
                        })));
    }

    /** {@code /spegtate whitelist <list|add|remove|on|off> [player]}. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> whitelist() {
        return ClientCommandManager.literal("whitelist")
                .then(ClientCommandManager.literal("list").executes(ctx -> {
                    run(ctx.getSource(), "whitelist", List.of("list"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("on").executes(ctx -> {
                    run(ctx.getSource(), "whitelist", List.of("on"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("off").executes(ctx -> {
                    run(ctx.getSource(), "whitelist", List.of("off"));
                    return 1;
                }))
                // add and remove both complete against players online with the mod. That is the
                // only name list this client holds; anyone else has to be typed in full, which
                // is unavoidable since the whitelist itself lives on the service.
                .then(ClientCommandManager.literal("add").then(
                        ClientCommandManager.argument("player", StringArgumentType.word())
                                .suggests(onlinePlayers())
                                .executes(ctx -> {
                                    run(ctx.getSource(), "whitelist", List.of("add",
                                            StringArgumentType.getString(ctx, "player")));
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("remove").then(
                        ClientCommandManager.argument("player", StringArgumentType.word())
                                .suggests(onlinePlayers())
                                .executes(ctx -> {
                                    run(ctx.getSource(), "whitelist", List.of("remove",
                                            StringArgumentType.getString(ctx, "player")));
                                    return 1;
                                })))
                .executes(ctx -> {
                    SpectateChat.error(Text.literal(
                            "Usage: /spegtate whitelist <list|add|remove|on|off> [player]"));
                    return 0;
                });
    }

    /**
     * Completes against players currently online with the mod.
     *
     * <p>From the roster this client already receives, so completion costs no network round trip
     * and works the moment the menu would show the same names.
     */
    private static SuggestionProvider<FabricClientCommandSource> onlinePlayers() {
        return (ctx, builder) -> {
            String typed = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
            for (SpectateRoster.Peer peer : SpectateRoster.peers()) {
                String name = peer.name();
                if (name != null && name.toLowerCase(java.util.Locale.ROOT).startsWith(typed)) {
                    builder.suggest(name);
                }
            }
            return builder.buildFuture();
        };
    }

    // ---- execution ----

    private static void run(FabricClientCommandSource source, String command, List<String> args) {
        SpectateManager manager = SpectateManager.get();
        if (!manager.isServiceReady()) {
            SpectateChat.error(SpectateLang.text("admin.not_connected"));
            return;
        }
        MinecraftClient mc = source.getClient();
        manager.sendAdminCommand(command, args, (lines, error) ->
                mc.execute(() -> report(lines, error)));
    }

    /**
     * {@code playerlist}, paginated on the client.
     *
     * <p>Paging is done here rather than on the service because only the client knows how much
     * chat is on screen, and only the client can make the links clickable.
     */
    private static void runPlayerList(FabricClientCommandSource source, int page) {
        SpectateManager manager = SpectateManager.get();
        if (!manager.isServiceReady()) {
            SpectateChat.error(SpectateLang.text("admin.not_connected"));
            return;
        }
        MinecraftClient mc = source.getClient();
        manager.sendAdminCommand("playerlist", List.of(), (lines, error) ->
                mc.execute(() -> {
                    if (lines == null) {
                        SpectateChat.error(SpectateLang.text("admin.no_response"));
                        return;
                    }
                    if (error) {
                        lines.forEach(l -> SpectateChat.error(Text.literal(l)));
                        return;
                    }
                    // The first line is the service's header; the rest are names.
                    List<String> names = lines.size() > 1
                            ? new ArrayList<>(lines.subList(1, lines.size()))
                            : List.of();
                    lastPlayerList = List.copyOf(names);
                    showPage(lines.isEmpty() ? "" : lines.get(0), names, page);
                }));
    }

    /**
     * Render one page of the player list, with links when there is more than one.
     *
     * <p>A page number past the end is clamped rather than refused: the list changes between the
     * command and the click, and an error for a link the mod itself drew would be the mod's
     * fault, not the user's.
     */
    private static void showPage(String header, List<String> names, int page) {
        if (names.isEmpty()) {
            SpectateChat.info(Text.literal(header));
            return;
        }
        int pages = (names.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int current = Math.max(1, Math.min(page, pages));
        int from = (current - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, names.size());

        SpectateChat.info(Text.literal(header));
        for (String name : names.subList(from, to)) {
            SpectateChat.info(Text.literal(name));
        }
        if (pages > 1) {
            SpectateChat.raw(pageLinks(current, pages));
        }
    }

    /** The {@code << Prev  Page n/m  Next >>} row, with only the usable links clickable. */
    private static MutableText pageLinks(int current, int pages) {
        MutableText row = Text.empty();
        row.append(pageLink(SpectateLang.string("admin.page_prev"), current - 1, current > 1));
        row.append(Text.literal("  ")
                .append(SpectateLang.text("admin.page_of", current, pages))
                .formatted(Formatting.GRAY));
        row.append(Text.literal("  "));
        row.append(pageLink(SpectateLang.string("admin.page_next"), current + 1, current < pages));
        return row;
    }

    /**
     * One page link.
     *
     * <p>A disabled link is drawn dark grey with no click handler rather than omitted, so the row
     * does not change width as you move through the pages.
     */
    private static MutableText pageLink(String label, int target, boolean enabled) {
        if (!enabled) {
            return Text.literal(label).formatted(Formatting.DARK_GRAY);
        }
        return Text.literal(label).setStyle(Style.EMPTY
                .withColor(Formatting.GREEN)
                .withClickEvent(new ClickEvent.RunCommand("/spegtate playerlist " + target))
                .withHoverEvent(new HoverEvent.ShowText(
                        SpectateLang.text("admin.page_of", target, target))));
    }

    /**
     * Show a reply. Runs on the client thread.
     *
     * <p>Null lines mean nothing answered, which is also what a service too old to know the
     * command looks like, since it drops the frame silently.
     *
     * <p>Colour follows the three fixed meanings: red for a failure, green for a line that
     * reports a completed action, grey for everything else. The service marks the whole reply as
     * an error or not; within a successful reply the only green lines are the ones that changed
     * something, which is why {@code changed} is tested per line rather than per reply.
     */
    private static void report(List<String> lines, boolean error) {
        if (lines == null) {
            SpectateChat.error(SpectateLang.text("admin.no_response"));
            return;
        }
        for (String line : lines) {
            if (error) {
                SpectateChat.error(Text.literal(line));
            } else if (changed(line)) {
                SpectateChat.success(Text.literal(line));
            } else {
                SpectateChat.info(Text.literal(line));
            }
        }
    }

    /**
     * Whether a successful reply line reports a change rather than information.
     *
     * <p>Matched on the service's own phrasing. A prefix test rather than anything cleverer: the
     * service is in this repository and these are its exact words, so when one changes the other
     * changes with it.
     */
    private static boolean changed(String line) {
        return line.startsWith("Added ")
                || line.startsWith("Removed ")
                || line.startsWith("Whitelist enabled")
                || line.startsWith("Whitelist disabled");
    }
}
