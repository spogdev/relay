package dev.spog.spegtate.host;

import dev.spog.spegtate.SpectateClient;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ServerboundAcceptCodeOfConductPacket;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;

import java.util.List;
import java.util.function.Consumer;

/**
 * The host's configuration phase: hands the viewer the registries and tags recorded from the
 * target's own server, then finishes into the game phase.
 *
 * <p>This is where the handshake replay is spent. The packets were recorded in the order the
 * target's server sent them and are re-sent in that order, because the client applies them in
 * sequence -- known packs scope the registry data, and the registry data must land before the login
 * packet that references its ids.
 *
 * <p>The login packet is deliberately <em>not</em> sent here even though it was recorded with the
 * rest: it belongs to the game phase, so it is held back and sent by {@link #onFinished} once the
 * client has acknowledged the switch. Sending it during configuration would be a protocol error.
 */
public final class HostConfigurationListener implements ServerConfigurationPacketListener {
    private final Connection connection;
    private final List<Packet<?>> configurationPackets;
    private final Consumer<Connection> onFinished;

    /**
     * @param configurationPackets the recorded configuration-phase packets, in order, with the
     *     login packet already removed
     * @param onFinished called once the client is in the game phase and can receive world packets
     */
    public HostConfigurationListener(Connection connection, List<Packet<?>> configurationPackets,
                                     Consumer<Connection> onFinished) {
        this.connection = connection;
        this.configurationPackets = configurationPackets;
        this.onFinished = onFinished;
    }

    /** Sends the replayed configuration, then asks the client to finish. */
    public void begin() {
        for (Packet<?> packet : configurationPackets) {
            connection.send(packet);
        }
        connection.send(ClientboundFinishConfigurationPacket.INSTANCE);
        SpectateClient.LOGGER.debug("spectate host: replayed {} configuration packets",
                configurationPackets.size());
    }

    @Override
    public void handleConfigurationFinished(ServerboundFinishConfigurationPacket packet) {
        onFinished.accept(connection);
    }

    @Override
    public void handleSelectKnownPacks(ServerboundSelectKnownPacks packet) {
        // The client reports which packs it has. Nothing to negotiate: the registry data replayed
        // from the target is sent in full rather than diffed against known packs, so whatever the
        // client knows locally is irrelevant.
    }

    @Override
    public void handleAcceptCodeOfConduct(ServerboundAcceptCodeOfConductPacket packet) {
        // No code of conduct is sent, so this cannot arrive.
    }

    @Override
    public void handleClientInformation(ServerboundClientInformationPacket packet) {
        // Carries the viewer's language and view distance. The view distance that matters is the
        // target's capture radius, which the viewer cannot raise, so this is noted and ignored.
    }

    @Override
    public void handleKeepAlive(ServerboundKeepAlivePacket packet) {
        // No keep-alives are sent on an in-process channel: there is no timeout to prove liveness
        // against, and the channel's own state is authoritative.
    }

    @Override
    public void handlePong(ServerboundPongPacket packet) {
    }

    @Override
    public void handleCustomPayload(ServerboundCustomPayloadPacket packet) {
        // Mod channels. Not forwarded: a viewer is not a participant on the target's server, and
        // relaying its plugin messages there would make it one.
    }

    @Override
    public void handleResourcePackResponse(ServerboundResourcePackPacket packet) {
        // No resource pack is pushed; the viewer keeps its own.
    }

    @Override
    public void handleCustomClickAction(ServerboundCustomClickActionPacket packet) {
    }

    @Override
    public void handleCookieResponse(ServerboundCookieResponsePacket packet) {
    }

    @Override
    public PacketFlow flow() {
        return PacketFlow.SERVERBOUND;
    }

    @Override
    public ConnectionProtocol protocol() {
        return ConnectionProtocol.CONFIGURATION;
    }

    @Override
    public void onDisconnect(DisconnectionDetails details) {
        SpectateClient.LOGGER.debug("spectate host: configuration disconnect: {}",
                details.reason().getString());
    }

    @Override
    public boolean isAcceptingMessages() {
        return connection.isConnected();
    }
}
