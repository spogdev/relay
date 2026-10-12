package dev.spog.spegtate.host;

import com.mojang.authlib.GameProfile;
import dev.spog.spegtate.SpectateClient;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.c2s.common.CookieResponseC2SPacket;
import net.minecraft.network.packet.s2c.login.LoginSuccessS2CPacket;
import net.minecraft.network.listener.ServerLoginPacketListener;
import net.minecraft.network.packet.c2s.login.LoginQueryResponseC2SPacket;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.network.packet.c2s.login.LoginKeyC2SPacket;
import net.minecraft.network.packet.c2s.login.EnterConfigurationC2SPacket;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * The host's answer to the viewer's login, standing in for a server's login handler.
 *
 * <p>The sequence is the minimum the client accepts, with everything optional left out. There is no
 * encryption (the channel is in-process, so there is nothing to encrypt and no server to
 * authenticate against), no compression (the data never leaves the process) and no custom queries.
 * So a {@link LoginHelloC2SPacket} is answered immediately with
 * {@link LoginSuccessS2CPacket}, and the acknowledgement moves the connection to
 * configuration.
 *
 * <p>The profile handed back is the viewer's own. It has to be something, the client uses it for
 * its local player, and reusing the viewer's identity keeps the skin and name correct on the fake
 * local player that the camera later detaches from.
 */
public final class HostLoginListener implements ServerLoginPacketListener {
    private final ClientConnection connection;
    private final Consumer<GameProfile> onAcknowledged;
    private volatile GameProfile profile;

    public HostLoginListener(ClientConnection connection, Consumer<GameProfile> onAcknowledged) {
        this.connection = connection;
        this.onAcknowledged = onAcknowledged;
    }

    @Override
    public void onHello(LoginHelloC2SPacket packet) {
        // The client sends the name and uuid it is logged in as; there is nothing to verify here,
        // because this "server" serves exactly one client in its own process.
        UUID id = packet.profileId();
        this.profile = new GameProfile(id, packet.name());
        connection.send(new LoginSuccessS2CPacket(profile));
    }

    @Override
    public void onEnterConfiguration(EnterConfigurationC2SPacket packet) {
        onAcknowledged.accept(profile);
    }

    @Override
    public void onKey(LoginKeyC2SPacket packet) {
        // Unreachable: encryption is never requested, so the client never sends a key.
        SpectateClient.LOGGER.warn("spectate host: unexpected key packet");
    }

    @Override
    public void onQueryResponse(LoginQueryResponseC2SPacket packet) {
        // Unreachable: no custom queries are sent.
    }

    @Override
    public void onCookieResponse(CookieResponseC2SPacket packet) {
        // Unreachable: no cookie requests are sent.
    }

    @Override
    public NetworkSide getSide() {
        return NetworkSide.SERVERBOUND;
    }

    @Override
    public NetworkPhase getPhase() {
        return NetworkPhase.LOGIN;
    }

    @Override
    public void onDisconnected(DisconnectionInfo details) {
        SpectateClient.LOGGER.debug("spectate host: login disconnect: {}", details.reason().getString());
    }

    @Override
    public boolean isConnectionOpen() {
        return connection.isOpen();
    }
}
