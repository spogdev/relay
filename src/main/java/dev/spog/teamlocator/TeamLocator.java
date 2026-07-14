package dev.spog.teamlocator;

import dev.spog.teamlocator.net.BlockUpdatePayload;
import dev.spog.teamlocator.net.PingBroadcastPayload;
import dev.spog.teamlocator.net.PingPayload;
import dev.spog.teamlocator.net.PositionSnapshotPayload;
import dev.spog.teamlocator.net.TrustUpdatePayload;
import dev.spog.teamlocator.server.TeamRelay;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Registers every payload on both sides (so the codecs exist whether this
 * instance runs as a client or a dedicated server) and, on the server side, wires the relay that
 * routes positions and pings between mutually-trusting players.
 */
public class TeamLocator implements ModInitializer {
    public static final String MOD_ID = "teamlocator";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final TeamRelay relay = new TeamRelay();

    @Override
    public void onInitialize() {
        registerPayloads();

        // Server receives trust/block uploads and pings; it never sees a client's private lists,
        // only the effective outbound sets needed to route.
        ServerPlayNetworking.registerGlobalReceiver(TrustUpdatePayload.ID, (payload, context) ->
                relay.setSharing(context.player().getUUID(), payload.sharingWith()));

        ServerPlayNetworking.registerGlobalReceiver(BlockUpdatePayload.ID, (payload, context) ->
                relay.setBlocked(context.player().getUUID(), payload.blocked()));

        ServerPlayNetworking.registerGlobalReceiver(PingPayload.ID, (payload, context) ->
                relay.handlePing(context.server(), context.player()));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                relay.remove(handler.player.getUUID()));

        ServerTickEvents.END_SERVER_TICK.register(relay::tick);
    }

    private void registerPayloads() {
        // Serverbound (client -> server)
        PayloadTypeRegistry.serverboundPlay().register(TrustUpdatePayload.ID, TrustUpdatePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BlockUpdatePayload.ID, BlockUpdatePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PingPayload.ID, PingPayload.CODEC);

        // Clientbound (server -> client)
        PayloadTypeRegistry.clientboundPlay().register(PositionSnapshotPayload.ID, PositionSnapshotPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PingBroadcastPayload.ID, PingBroadcastPayload.CODEC);
    }
}
