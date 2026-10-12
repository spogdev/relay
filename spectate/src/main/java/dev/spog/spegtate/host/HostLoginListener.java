package dev.spog.spegtate.host;

import com.mojang.authlib.GameProfile;
import dev.spog.spegtate.SpectateClient;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.ServerLoginPacketListener;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The host's answer to the viewer's login, standing in for a server's login handler.
 *
 * <p>The sequence is the minimum the client accepts, with everything optional left out. There is no
 * encryption (the channel is in-process, so there is nothing to encrypt and no server to
 * authenticate against), no compression (the data never leaves the process) and no custom queries.
 * So a {@link ServerboundHelloPacket} is answered immediately with
 * {@link ClientboundLoginFinishedPacket}, and the acknowledgement moves the connection to
 * configuration.
 *
 * <p>The profile handed back is the viewer's own. It has to be something, the client uses it for
 * its local player, and reusing the viewer's identity keeps the skin and name correct on the fake
 * local player that the camera later detaches from.
 */
public final class HostLoginListener implements ServerLoginPacketListener {
    private final Connection connection;
    private final Consumer<GameProfile> onAcknowledged;
    private volatile GameProfile profile;

    public HostLoginListener(Connection connection, Consumer<GameProfile> onAcknowledged) {
        this.connection = connection;
        this.onAcknowledged = onAcknowledged;
    }

    @Override
    public void handleHello(ServerboundHelloPacket packet) {
        // The client sends the name and uuid it is logged in as; there is nothing to verify here,
        // because this "server" serves exactly one client in its own process.
        UUID id = packet.profileId();
        this.profile = new GameProfile(id, packet.name());
        // The session id is the profile id: this host serves exactly one client, in its own
        // process, so there is no separate session to track.
        connection.send(new ClientboundLoginFinishedPacket(profile, id));
    }

    @Override
    public void handleLoginAcknowledgement(ServerboundLoginAcknowledgedPacket packet) {
        onAcknowledged.accept(profile);
    }

    @Override
    public void handleKey(ServerboundKeyPacket packet) {
        // Unreachable: encryption is never requested, so the client never sends a key.
        SpectateClient.LOGGER.warn("spectate host: unexpected key packet");
    }

    @Override
    public void handleCustomQueryPacket(ServerboundCustomQueryAnswerPacket packet) {
        // Unreachable: no custom queries are sent.
    }

    @Override
    public void handleCookieResponse(ServerboundCookieResponsePacket packet) {
        // Unreachable: no cookie requests are sent.
    }

    @Override
    public PacketFlow flow() {
        return PacketFlow.SERVERBOUND;
    }

    @Override
    public ConnectionProtocol protocol() {
        return ConnectionProtocol.LOGIN;
    }

    @Override
    public void onDisconnect(DisconnectionDetails details) {
        SpectateClient.LOGGER.debug("spectate host: login disconnect: {}", details.reason().getString());
    }

    @Override
    public boolean isAcceptingMessages() {
        return connection.isConnected();
    }
}
