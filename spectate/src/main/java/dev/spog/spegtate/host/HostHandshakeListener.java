package dev.spog.spegtate.host;

import dev.spog.spegtate.SpectateClient;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.handshake.ServerHandshakePacketListener;

import java.util.function.Consumer;

/**
 * The host's answer to the viewer's intention packet, which is the first thing a joining client
 * sends.
 *
 * <p>This phase is easy to forget, and omitting it is not a subtle failure: the client sends
 * {@link ClientIntentionPacket} before anything else, and a connection whose inbound listener is
 * the login listener throws a {@code ClassCastException} on arrival, because the packet's
 * {@code handle} casts to this interface. That was the first thing a real session hit.
 *
 * <p>Nothing is validated here. A real server checks the client's protocol version and may refuse
 * it, but both ends of this connection are the same process on the same version, so there is
 * nothing to disagree about.
 */
public final class HostHandshakeListener implements ServerHandshakePacketListener {
    private final Connection connection;
    private final Consumer<ClientIntentionPacket> onIntention;

    public HostHandshakeListener(Connection connection, Consumer<ClientIntentionPacket> onIntention) {
        this.connection = connection;
        this.onIntention = onIntention;
    }

    @Override
    public void handleIntention(ClientIntentionPacket packet) {
        SpectateClient.LOGGER.debug("spectate host: intention {}", packet.intention());
        onIntention.accept(packet);
    }

    @Override
    public PacketFlow flow() {
        return PacketFlow.SERVERBOUND;
    }

    @Override
    public ConnectionProtocol protocol() {
        return ConnectionProtocol.HANDSHAKING;
    }

    @Override
    public void onDisconnect(DisconnectionDetails details) {
        SpectateClient.LOGGER.debug("spectate host: handshake disconnect: {}",
                details.reason().getString());
    }

    @Override
    public boolean isAcceptingMessages() {
        return connection.isConnected();
    }
}
