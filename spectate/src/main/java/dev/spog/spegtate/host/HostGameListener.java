package dev.spog.spegtate.host;

import dev.spog.spegtate.SpectateClient;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerGamePacketListener;

/**
 * Absorbs everything the viewer's client sends, and acts on none of it.
 *
 * <p>Required rather than optional: a client in the game phase sends continuously -- movement,
 * keep-alives, its client settings, a swing when the player clicks -- and a connection with no
 * inbound listener throws {@code IllegalStateException} on the first one. So every handler exists
 * and every one does nothing.
 *
 * <p>Doing nothing is also the correct behaviour. The viewer is not a participant on the target's
 * server: it has no entity there and no permission to act. Its movement in particular must be
 * discarded, because the camera follows the target and is driven by
 * {@link HostDriver#updateTargetPosition} rather than by the viewer's own input. Forwarding any of
 * this to the target's server would make the viewer a participant, which is exactly what this
 * feature must not do.
 *
 * <p>Generated against the interface rather than hand-written, so a handler added in a future
 * version fails the build with a missing-override error rather than at runtime on the first packet
 * of that kind.
 */
public final class HostGameListener implements ServerGamePacketListener {
    private final Connection connection;

    public HostGameListener(Connection connection) {
        this.connection = connection;
    }

    @Override
    public PacketFlow flow() {
        return PacketFlow.SERVERBOUND;
    }

    @Override
    public ConnectionProtocol protocol() {
        return ConnectionProtocol.PLAY;
    }

    @Override
    public void onDisconnect(DisconnectionDetails details) {
        SpectateClient.LOGGER.debug("spectate host: viewer disconnected: {}",
                details.reason().getString());
    }

    @Override
    public boolean isAcceptingMessages() {
        return connection.isConnected();
    }

    // ---- everything below is deliberately empty; see the class javadoc ----

    @Override
    public void handleAcceptPlayerLoad(net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket packet) {
    }

    @Override
    public void handleAcceptTeleportPacket(net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket packet) {
    }

    @Override
    public void handleAnimate(net.minecraft.network.protocol.game.ServerboundSwingPacket packet) {
    }

    @Override
    public void handleAttack(net.minecraft.network.protocol.game.ServerboundAttackPacket packet) {
    }

    @Override
    public void handleBlockEntityTagQuery(net.minecraft.network.protocol.game.ServerboundBlockEntityTagQueryPacket packet) {
    }

    @Override
    public void handleBundleItemSelectedPacket(net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket packet) {
    }

    @Override
    public void handleChangeDifficulty(net.minecraft.network.protocol.game.ServerboundChangeDifficultyPacket packet) {
    }

    @Override
    public void handleChangeGameMode(net.minecraft.network.protocol.game.ServerboundChangeGameModePacket packet) {
    }

    @Override
    public void handleChat(net.minecraft.network.protocol.game.ServerboundChatPacket packet) {
    }

    @Override
    public void handleChatAck(net.minecraft.network.protocol.game.ServerboundChatAckPacket packet) {
    }

    @Override
    public void handleChatCommand(net.minecraft.network.protocol.game.ServerboundChatCommandPacket packet) {
    }

    @Override
    public void handleChatSessionUpdate(net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket packet) {
    }

    @Override
    public void handleChunkBatchReceived(net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket packet) {
    }

    @Override
    public void handleClientCommand(net.minecraft.network.protocol.game.ServerboundClientCommandPacket packet) {
    }

    @Override
    public void handleClientInformation(net.minecraft.network.protocol.common.ServerboundClientInformationPacket packet) {
    }

    @Override
    public void handleClientTickEnd(net.minecraft.network.protocol.game.ServerboundClientTickEndPacket packet) {
    }

    @Override
    public void handleConfigurationAcknowledged(net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket packet) {
    }

    @Override
    public void handleContainerButtonClick(net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket packet) {
    }

    @Override
    public void handleContainerClick(net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet) {
    }

    @Override
    public void handleContainerClose(net.minecraft.network.protocol.game.ServerboundContainerClosePacket packet) {
    }

    @Override
    public void handleContainerSlotStateChanged(net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket packet) {
    }

    @Override
    public void handleCookieResponse(net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket packet) {
    }

    @Override
    public void handleCustomClickAction(net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket packet) {
    }

    @Override
    public void handleCustomCommandSuggestions(net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket packet) {
    }

    @Override
    public void handleCustomPayload(net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket packet) {
    }

    @Override
    public void handleDebugSubscriptionRequest(net.minecraft.network.protocol.game.ServerboundDebugSubscriptionRequestPacket packet) {
    }

    @Override
    public void handleEditBook(net.minecraft.network.protocol.game.ServerboundEditBookPacket packet) {
    }

    @Override
    public void handleEntityTagQuery(net.minecraft.network.protocol.game.ServerboundEntityTagQueryPacket packet) {
    }

    @Override
    public void handleInteract(net.minecraft.network.protocol.game.ServerboundInteractPacket packet) {
    }

    @Override
    public void handleJigsawGenerate(net.minecraft.network.protocol.game.ServerboundJigsawGeneratePacket packet) {
    }

    @Override
    public void handleKeepAlive(net.minecraft.network.protocol.common.ServerboundKeepAlivePacket packet) {
    }

    @Override
    public void handleLockDifficulty(net.minecraft.network.protocol.game.ServerboundLockDifficultyPacket packet) {
    }

    @Override
    public void handleMovePlayer(net.minecraft.network.protocol.game.ServerboundMovePlayerPacket packet) {
    }

    @Override
    public void handleMoveVehicle(net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket packet) {
    }

    @Override
    public void handlePaddleBoat(net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket packet) {
    }

    @Override
    public void handlePickItemFromBlock(net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket packet) {
    }

    @Override
    public void handlePickItemFromEntity(net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket packet) {
    }

    @Override
    public void handlePingRequest(net.minecraft.network.protocol.ping.ServerboundPingRequestPacket packet) {
    }

    @Override
    public void handlePlaceRecipe(net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket packet) {
    }

    @Override
    public void handlePlayerAbilities(net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket packet) {
    }

    @Override
    public void handlePlayerAction(net.minecraft.network.protocol.game.ServerboundPlayerActionPacket packet) {
    }

    @Override
    public void handlePlayerCommand(net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket packet) {
    }

    @Override
    public void handlePlayerInput(net.minecraft.network.protocol.game.ServerboundPlayerInputPacket packet) {
    }

    @Override
    public void handlePong(net.minecraft.network.protocol.common.ServerboundPongPacket packet) {
    }

    @Override
    public void handleRecipeBookChangeSettingsPacket(net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket packet) {
    }

    @Override
    public void handleRecipeBookSeenRecipePacket(net.minecraft.network.protocol.game.ServerboundRecipeBookSeenRecipePacket packet) {
    }

    @Override
    public void handleRenameItem(net.minecraft.network.protocol.game.ServerboundRenameItemPacket packet) {
    }

    @Override
    public void handleResourcePackResponse(net.minecraft.network.protocol.common.ServerboundResourcePackPacket packet) {
    }

    @Override
    public void handleSeenAdvancements(net.minecraft.network.protocol.game.ServerboundSeenAdvancementsPacket packet) {
    }

    @Override
    public void handleSelectTrade(net.minecraft.network.protocol.game.ServerboundSelectTradePacket packet) {
    }

    @Override
    public void handleSetBeaconPacket(net.minecraft.network.protocol.game.ServerboundSetBeaconPacket packet) {
    }

    @Override
    public void handleSetCarriedItem(net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket packet) {
    }

    @Override
    public void handleSetCommandBlock(net.minecraft.network.protocol.game.ServerboundSetCommandBlockPacket packet) {
    }

    @Override
    public void handleSetCommandMinecart(net.minecraft.network.protocol.game.ServerboundSetCommandMinecartPacket packet) {
    }

    @Override
    public void handleSetCreativeModeSlot(net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket packet) {
    }

    @Override
    public void handleSetGameRule(net.minecraft.network.protocol.game.ServerboundSetGameRulePacket packet) {
    }

    @Override
    public void handleSetJigsawBlock(net.minecraft.network.protocol.game.ServerboundSetJigsawBlockPacket packet) {
    }

    @Override
    public void handleSetStructureBlock(net.minecraft.network.protocol.game.ServerboundSetStructureBlockPacket packet) {
    }

    @Override
    public void handleSetTestBlock(net.minecraft.network.protocol.game.ServerboundSetTestBlockPacket packet) {
    }

    @Override
    public void handleSignUpdate(net.minecraft.network.protocol.game.ServerboundSignUpdatePacket packet) {
    }

    @Override
    public void handleSignedChatCommand(net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket packet) {
    }

    @Override
    public void handleSpectateEntity(net.minecraft.network.protocol.game.ServerboundSpectateEntityPacket packet) {
    }

    /**
     * Moves the viewer's camera to the player it clicked in the spectator bar.
     *
     * <p>Vanilla's spectator menu sends this to the server when a player is clicked, and the
     * viewer's "server" is this loopback host -- so the click arrived here and did nothing,
     * because this was an empty stub. The real server never sees it and must not: the viewer is
     * not a player there.
     *
     * <p>The packet names a player by uuid, which is all that is needed; the camera resolves the
     * entity itself.
     *
     * <p>Handed to the client thread rather than acted on here. This listener runs on the
     * loopback connection's Netty event loop, and the focus change reads the viewer's world and
     * assigns its camera entity -- neither of which may be touched off the client thread.
     */
    @Override
    public void handleTeleportToEntityPacket(net.minecraft.network.protocol.game.ServerboundTeleportToEntityPacket packet) {
        // Cast here rather than through a static helper on the accessor: a mixin targeting a
        // class may not declare a non-private static method, and doing so failed at class-load
        // time in a way that took down every server connection.
        java.util.UUID player =
                ((dev.spog.spegtate.mixin.SpectatorTeleportAccessor) (Object) packet)
                        .spegtate$uuid();
        dev.spog.spegtate.SpectateManager.get().onClientThread(() -> {
            var session = dev.spog.spegtate.SpectateManager.get().viewing();
            if (session != null) {
                session.focusOn(player);
            }
        });
    }

    @Override
    public void handleTestInstanceBlockAction(net.minecraft.network.protocol.game.ServerboundTestInstanceBlockActionPacket packet) {
    }

    @Override
    public void handleUseItem(net.minecraft.network.protocol.game.ServerboundUseItemPacket packet) {
    }

    @Override
    public void handleUseItemOn(net.minecraft.network.protocol.game.ServerboundUseItemOnPacket packet) {
    }
}
