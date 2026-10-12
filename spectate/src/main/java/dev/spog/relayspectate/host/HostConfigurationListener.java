package dev.spog.relayspectate.host;

import dev.spog.relayspectate.SpectateClient;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.c2s.common.ClientOptionsC2SPacket;
import net.minecraft.network.packet.c2s.common.CommonPongC2SPacket;
import net.minecraft.network.packet.c2s.common.CustomClickActionC2SPacket;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;
import net.minecraft.network.packet.c2s.common.ResourcePackStatusC2SPacket;
import net.minecraft.network.packet.c2s.config.AcceptCodeOfConductC2SPacket;
import net.minecraft.network.packet.c2s.config.ReadyC2SPacket;
import net.minecraft.network.packet.c2s.config.SelectKnownPacksC2SPacket;
import net.minecraft.network.packet.s2c.config.ReadyS2CPacket;
import net.minecraft.network.listener.ServerConfigurationPacketListener;

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
    private final ClientConnection connection;
    private final List<Packet<?>> configurationPackets;
    private final Consumer<ClientConnection> onFinished;

    /**
     * @param configurationPackets the recorded configuration-phase packets, in order, with the
     *     login packet already removed
     * @param onFinished called once the client is in the game phase and can receive world packets
     */
    public HostConfigurationListener(ClientConnection connection, List<Packet<?>> configurationPackets,
                                     Consumer<ClientConnection> onFinished) {
        this.connection = connection;
        this.configurationPackets = configurationPackets;
        this.onFinished = onFinished;
    }

    /** Sends the replayed configuration, then asks the client to finish. */
    public void begin() {
        for (Packet<?> packet : configurationPackets) {
            connection.send(packet);
        }
        connection.send(ReadyS2CPacket.INSTANCE);
        SpectateClient.LOGGER.debug("spectate host: replayed {} configuration packets",
                configurationPackets.size());
    }

    @Override
    public void onReady(ReadyC2SPacket packet) {
        onFinished.accept(connection);
    }

    @Override
    public void onSelectKnownPacks(SelectKnownPacksC2SPacket packet) {
        // The client reports which packs it has. Nothing to negotiate: the registry data replayed
        // from the target is sent in full rather than diffed against known packs, so whatever the
        // client knows locally is irrelevant.
    }

    @Override
    public void onAcceptCodeOfConduct(AcceptCodeOfConductC2SPacket packet) {
        // No code of conduct is sent, so this cannot arrive.
    }

    @Override
    public void onClientOptions(ClientOptionsC2SPacket packet) {
        // Carries the viewer's language and view distance. The view distance that matters is the
        // target's capture radius, which the viewer cannot raise, so this is noted and ignored.
    }

    @Override
    public void onKeepAlive(KeepAliveC2SPacket packet) {
        // No keep-alives are sent on an in-process channel: there is no timeout to prove liveness
        // against, and the channel's own state is authoritative.
    }

    @Override
    public void onPong(CommonPongC2SPacket packet) {
    }

    @Override
    public void onCustomPayload(CustomPayloadC2SPacket packet) {
        // Mod channels. Not forwarded: a viewer is not a participant on the target's server, and
        // relaying its plugin messages there would make it one.
    }

    @Override
    public void onResourcePackStatus(ResourcePackStatusC2SPacket packet) {
        // No resource pack is pushed; the viewer keeps its own.
    }

    @Override
    public void onCustomClickAction(CustomClickActionC2SPacket packet) {
    }

    @Override
    public void onCookieResponse(
            net.minecraft.network.packet.c2s.common.CookieResponseC2SPacket packet) {
        // No cookie is ever requested, so this cannot arrive.
    }

    @Override
    public NetworkSide getSide() {
        return NetworkSide.SERVERBOUND;
    }

    @Override
    public NetworkPhase getPhase() {
        return NetworkPhase.CONFIGURATION;
    }

    @Override
    public void onDisconnected(DisconnectionInfo info) {
        SpectateClient.LOGGER.debug("spectate host: configuration disconnect: {}",
                info.reason().getString());
    }

    @Override
    public boolean isConnectionOpen() {
        return connection.isOpen();
    }
}
