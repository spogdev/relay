package dev.spog.teamlocator.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code /relaytestplayer} — puts a made-up teammate where you are standing, for trying out the HUD,
 * markers and routes without a second account.
 *
 * <p>A test player exists only on this client: nothing is sent to the relay. It is fed into
 * {@link ClientState} the way a real teammate's position arrives, so everything that shows teammates
 * shows it, and is reported again every tick so it never goes stale. There is no player model; it is
 * only a position. {@code /relaytestplayer clear} removes them all, and leaving the server does too.
 */
@Environment(EnvType.CLIENT)
public final class TestPlayers {
    /** Ping colours handed out in turn, so several test players can be told apart. */
    private static final String[] COLORS = {"#e34234", "#2654e3", "#3cb44b", "#f58231", "#911eb4"};

    private record TestPlayer(UUID id, String name, double x, double y, double z, Identifier dimension,
                              String color) {
        TrackedPos position() {
            return new TrackedPos(id, x, y, z, dimension, List.of(), 20.0f, color);
        }
    }

    private static final List<TestPlayer> players = new ArrayList<>();

    private TestPlayers() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommandManager.literal("relaytestplayer")
                        .executes(ctx -> {
                            summon(ctx.getSource());
                            return 1;
                        })
                        .then(ClientCommandManager.literal("clear").executes(ctx -> {
                            int count = players.size();
                            clear();
                            ctx.getSource().sendFeedback(RelayChat.primary(
                                    RelayLang.text("relay.testplayer.cleared", RelayChat.value(String.valueOf(count)))));
                            return 1;
                        }))));
    }

    private static void summon(FabricClientCommandSource source) {
        ClientPlayerEntity player = source.getPlayer();
        int number = players.size() + 1;
        String name = "TestPlayer" + number;
        UUID id = UUID.nameUUIDFromBytes(("relay-test-player:" + name).getBytes(StandardCharsets.UTF_8));
        TestPlayer added = new TestPlayer(id, name, player.getX(), player.getY(), player.getZ(),
                player.getEntityWorld().getRegistryKey().getValue(), COLORS[(number - 1) % COLORS.length]);
        players.add(added);
        ClientState.updatePosition(added.position());
        source.sendFeedback(RelayChat.primary(RelayLang.text("relay.testplayer.summoned",
                RelayChat.value(name),
                RelayChat.value(String.format("%d, %d, %d",
                        (int) Math.floor(added.x()), (int) Math.floor(added.y()), (int) Math.floor(added.z()))))));
    }

    /** Reports every test player again, so none of them expires like a teammate who went quiet. */
    public static void tick() {
        for (TestPlayer p : players) {
            ClientState.updatePosition(p.position());
        }
    }

    /** The test player's name, or {@code null} if this is not one. */
    public static String name(UUID id) {
        for (TestPlayer p : players) {
            if (p.id().equals(id)) {
                return p.name();
            }
        }
        return null;
    }

    /** Removes every test player. Their positions go too, so nothing lingers until it expires. */
    public static void clear() {
        for (TestPlayer p : players) {
            ClientState.remove(p.id());
        }
        players.clear();
    }
}
