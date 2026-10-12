package dev.spog.spegtate.host;

import dev.spog.spegtate.SpectateClient;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.c2s.handshake.HandshakeC2SPacket;
import net.minecraft.network.listener.ServerHandshakePacketListener;

import java.util.function.Consumer;

/**
 * The host's answer to the viewer's intention packet, which is the first thing a joining client
 * sends.
 *
 * <p>This phase is easy to forget, and omitting it is not a subtle failure: the client sends
 * {@link HandshakeC2SPacket} before anything else, and a connection whose inbound listener is
 * the login listener throws a {@code ClassCastException} on arrival, because the packet's
 * {@code handle} casts to this interface. That was the first thing a real session hit.
 *
 * <p>Nothing is validated here. A real server checks the client's protocol version and may refuse
 * it, but both ends of this connection are the same process on the same version, so there is
 * nothing to disagree about.
 */
public final class HostHandshakeListener implements ServerHandshakePacketListener {
    private final ClientConnection connection;
    private final Consumer<HandshakeC2SPacket> onIntention;

    public HostHandshakeListener(ClientConnection connection, Consumer<HandshakeC2SPacket> onIntention) {
        this.connection = connection;
        this.onIntention = onIntention;
    }

    @Override
    public void onHandshake(HandshakeC2SPacket packet) {
        SpectateClient.LOGGER.debug("spectate host: intention {}", packet.intendedState());
        onIntention.accept(packet);
    }

    @Override
    public NetworkSide getSide() {
        return NetworkSide.SERVERBOUND;
    }

    @Override
    public NetworkPhase getPhase() {
        return NetworkPhase.HANDSHAKING;
    }

    @Override
    public void onDisconnected(DisconnectionInfo details) {
        SpectateClient.LOGGER.debug("spectate host: handshake disconnect: {}",
                details.reason().getString());
    }

    @Override
    public boolean isConnectionOpen() {
        return connection.isOpen();
    }
}
