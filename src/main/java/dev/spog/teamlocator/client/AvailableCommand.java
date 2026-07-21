package dev.spog.teamlocator.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code /available} — asks the relay which mutually-trusting teammates would <em>actually see</em>
 * an alert sent right now, and prints their names.
 *
 * <p>"Actually see" is the operative promise: delivery alone isn't enough, because display is
 * decided receiver-side (their mute list, their cross-server toggle, and the known-server gate —
 * a receiver who doesn't have our server in their server list or direct-connect box drops the
 * alert). So the relay silently probes each candidate's client, which evaluates the real
 * {@link PingHandler#wouldDisplay} gate and answers without showing anything. Only verified
 * yeses are listed; peers on pre-probe mod versions can't be verified and are not listed.
 */
@Environment(EnvType.CLIENT)
public final class AvailableCommand {
    private AvailableCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("available").executes(ctx -> {
                    run(ctx.getSource());
                    return 1;
                })));
    }

    private static void run(FabricClientCommandSource source) {
        if (!TeamLocatorClient.RELAY.isReady()) {
            source.sendError(Component.translatable("relay.available.not_connected"));
            return;
        }
        source.sendFeedback(RelayChat.primary(Component.translatable("relay.available.checking")));
        Minecraft mc = source.getClient();
        TeamLocatorClient.RELAY.queryAvailability(receivers ->
                mc.execute(() -> report(mc, receivers)));
    }

    /** Runs on the game thread. {@code receivers} null means the relay never answered. */
    private static void report(Minecraft mc, List<UUID> receivers) {
        if (receivers == null) {
            RelayChat.send(Component.translatable("relay.available.timeout"));
        } else if (receivers.isEmpty()) {
            RelayChat.send(Component.translatable("relay.available.none"));
        } else {
            String names = receivers.stream()
                    .map(id -> PingHandler.displayName(mc, id))
                    .collect(Collectors.joining(", "));
            RelayChat.send(Component.translatable("relay.available.list",
                    RelayChat.value(names)));
        }
    }
}
