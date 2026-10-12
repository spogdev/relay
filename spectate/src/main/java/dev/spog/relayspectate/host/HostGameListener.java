package dev.spog.relayspectate.host;

import dev.spog.relayspectate.SpectateClient;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.listener.ServerPlayPacketListener;

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
public final class HostGameListener implements ServerPlayPacketListener {
    private final ClientConnection connection;

    public HostGameListener(ClientConnection connection) {
        this.connection = connection;
    }

    @Override
    public NetworkSide getSide() {
        return NetworkSide.SERVERBOUND;
    }

    @Override
    public NetworkPhase getPhase() {
        return NetworkPhase.PLAY;
    }

    @Override
    public void onDisconnected(DisconnectionInfo info) {
        SpectateClient.LOGGER.debug("spectate host: viewer disconnected: {}",
                info.reason().getString());
    }

    @Override
    public boolean isConnectionOpen() {
        return connection.isOpen();
    }

    // ---- everything below is deliberately empty; see the class javadoc ----

    @Override
    public void onAcknowledgeChunks(net.minecraft.network.packet.c2s.play.AcknowledgeChunksC2SPacket packet) {
    }

    @Override
    public void onAcknowledgeReconfiguration(net.minecraft.network.packet.c2s.play.AcknowledgeReconfigurationC2SPacket packet) {
    }

    @Override
    public void onAdvancementTab(net.minecraft.network.packet.c2s.play.AdvancementTabC2SPacket packet) {
    }

    @Override
    public void onBoatPaddleState(net.minecraft.network.packet.c2s.play.BoatPaddleStateC2SPacket packet) {
    }

    @Override
    public void onBookUpdate(net.minecraft.network.packet.c2s.play.BookUpdateC2SPacket packet) {
    }

    @Override
    public void onBundleItemSelected(net.minecraft.network.packet.c2s.play.BundleItemSelectedC2SPacket packet) {
    }

    @Override
    public void onButtonClick(net.minecraft.network.packet.c2s.play.ButtonClickC2SPacket packet) {
    }

    @Override
    public void onChangeGameMode(net.minecraft.network.packet.c2s.play.ChangeGameModeC2SPacket packet) {
    }

    @Override
    public void onChatCommandSigned(net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket packet) {
    }

    @Override
    public void onChatMessage(net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket packet) {
    }

    @Override
    public void onClickSlot(net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket packet) {
    }

    @Override
    public void onClientCommand(net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket packet) {
    }

    @Override
    public void onClientOptions(net.minecraft.network.packet.c2s.common.ClientOptionsC2SPacket packet) {
    }

    @Override
    public void onClientStatus(net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket packet) {
    }

    @Override
    public void onClientTickEnd(net.minecraft.network.packet.c2s.play.ClientTickEndC2SPacket packet) {
    }

    @Override
    public void onCloseHandledScreen(net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket packet) {
    }

    @Override
    public void onCommandExecution(net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket packet) {
    }

    @Override
    public void onCraftRequest(net.minecraft.network.packet.c2s.play.CraftRequestC2SPacket packet) {
    }

    @Override
    public void onCreativeInventoryAction(net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket packet) {
    }

    @Override
    public void onCustomClickAction(net.minecraft.network.packet.c2s.common.CustomClickActionC2SPacket packet) {
    }

    @Override
    public void onCustomPayload(net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket packet) {
    }

    @Override
    public void onDebugSubscriptionRequest(net.minecraft.network.packet.c2s.play.DebugSubscriptionRequestC2SPacket packet) {
    }

    @Override
    public void onHandSwing(net.minecraft.network.packet.c2s.play.HandSwingC2SPacket packet) {
    }

    @Override
    public void onJigsawGenerating(net.minecraft.network.packet.c2s.play.JigsawGeneratingC2SPacket packet) {
    }

    @Override
    public void onKeepAlive(net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket packet) {
    }

    @Override
    public void onMessageAcknowledgment(net.minecraft.network.packet.c2s.play.MessageAcknowledgmentC2SPacket packet) {
    }

    @Override
    public void onPickItemFromBlock(net.minecraft.network.packet.c2s.play.PickItemFromBlockC2SPacket packet) {
    }

    @Override
    public void onPickItemFromEntity(net.minecraft.network.packet.c2s.play.PickItemFromEntityC2SPacket packet) {
    }

    @Override
    public void onPlayerAction(net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket packet) {
    }

    @Override
    public void onPlayerInput(net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket packet) {
    }

    @Override
    public void onPlayerInteractBlock(net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket packet) {
    }

    @Override
    public void onPlayerInteractEntity(net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket packet) {
    }

    @Override
    public void onPlayerInteractItem(net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket packet) {
    }

    @Override
    public void onPlayerLoaded(net.minecraft.network.packet.c2s.play.PlayerLoadedC2SPacket packet) {
    }

    @Override
    public void onPlayerMove(net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket packet) {
    }

    @Override
    public void onPlayerSession(net.minecraft.network.packet.c2s.play.PlayerSessionC2SPacket packet) {
    }

    @Override
    public void onPong(net.minecraft.network.packet.c2s.common.CommonPongC2SPacket packet) {
    }

    @Override
    public void onQueryBlockNbt(net.minecraft.network.packet.c2s.play.QueryBlockNbtC2SPacket packet) {
    }

    @Override
    public void onQueryEntityNbt(net.minecraft.network.packet.c2s.play.QueryEntityNbtC2SPacket packet) {
    }

    @Override
    public void onQueryPing(net.minecraft.network.packet.c2s.query.QueryPingC2SPacket packet) {
    }

    @Override
    public void onRecipeBookData(net.minecraft.network.packet.c2s.play.RecipeBookDataC2SPacket packet) {
    }

    @Override
    public void onRecipeCategoryOptions(net.minecraft.network.packet.c2s.play.RecipeCategoryOptionsC2SPacket packet) {
    }

    @Override
    public void onRenameItem(net.minecraft.network.packet.c2s.play.RenameItemC2SPacket packet) {
    }

    @Override
    public void onRequestCommandCompletions(net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket packet) {
    }

    @Override
    public void onResourcePackStatus(net.minecraft.network.packet.c2s.common.ResourcePackStatusC2SPacket packet) {
    }

    @Override
    public void onSelectMerchantTrade(net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket packet) {
    }

    @Override
    public void onSetTestBlock(net.minecraft.network.packet.c2s.play.SetTestBlockC2SPacket packet) {
    }

    @Override
    public void onSlotChangedState(net.minecraft.network.packet.c2s.play.SlotChangedStateC2SPacket packet) {
    }

    @Override
    public void onSpectatorTeleport(net.minecraft.network.packet.c2s.play.SpectatorTeleportC2SPacket packet) {
    }

    @Override
    public void onTeleportConfirm(net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket packet) {
    }

    @Override
    public void onTestInstanceBlockAction(net.minecraft.network.packet.c2s.play.TestInstanceBlockActionC2SPacket packet) {
    }

    @Override
    public void onUpdateBeacon(net.minecraft.network.packet.c2s.play.UpdateBeaconC2SPacket packet) {
    }

    @Override
    public void onUpdateCommandBlock(net.minecraft.network.packet.c2s.play.UpdateCommandBlockC2SPacket packet) {
    }

    @Override
    public void onUpdateCommandBlockMinecart(net.minecraft.network.packet.c2s.play.UpdateCommandBlockMinecartC2SPacket packet) {
    }

    @Override
    public void onUpdateDifficulty(net.minecraft.network.packet.c2s.play.UpdateDifficultyC2SPacket packet) {
    }

    @Override
    public void onUpdateDifficultyLock(net.minecraft.network.packet.c2s.play.UpdateDifficultyLockC2SPacket packet) {
    }

    @Override
    public void onUpdateJigsaw(net.minecraft.network.packet.c2s.play.UpdateJigsawC2SPacket packet) {
    }

    @Override
    public void onUpdatePlayerAbilities(net.minecraft.network.packet.c2s.play.UpdatePlayerAbilitiesC2SPacket packet) {
    }

    @Override
    public void onUpdateSelectedSlot(net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket packet) {
    }

    @Override
    public void onUpdateSign(net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket packet) {
    }

    @Override
    public void onUpdateStructureBlock(net.minecraft.network.packet.c2s.play.UpdateStructureBlockC2SPacket packet) {
    }

    @Override
    public void onVehicleMove(net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket packet) {
    }

    @Override
    public void onCookieResponse(
            net.minecraft.network.packet.c2s.cookie.CookieResponseC2SPacket packet) {
        // No cookie is ever requested, so this cannot arrive.
    }
}
