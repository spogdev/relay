package dev.spog.relayspectate.host;

import dev.spog.relayspectate.SpectateClient;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.login.LoginProtocols;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Walks the host's connection through login, configuration and into the game phase, then hands
 * control to the live stream.
 *
 * <p>The phase transitions are the fiddly part and the reason this is its own class. Each phase has
 * its own packet codec, and both ends must switch at the same point in the stream: the server
 * switches its outbound codec immediately after sending the packet that ends a phase, and its
 * inbound codec when the client acknowledges. Getting that order wrong decodes the next packet
 * against the wrong table, which surfaces as an opaque decoder error rather than anything that
 * names the real problem.
 *
 * <p>Sequence, with who drives each step:
 * <ol>
 *   <li>client sends Hello -&gt; {@link HostLoginListener} answers LoginFinished</li>
 *   <li>client acknowledges -&gt; this class switches both ends to configuration</li>
 *   <li>{@link HostConfigurationListener} replays the target's registries, then FinishConfiguration</li>
 *   <li>client acknowledges -&gt; this class switches both ends to game and sends the login packet</li>
 *   <li>live packets flow</li>
 * </ol>
 */
public final class HostDriver {
    private final Connection connection;
    private final List<Packet<?>> handshake;
    private final Supplier<RegistryAccess> registries;

    private volatile boolean inGame;

    /**
     * @param handshake the target's recorded handshake, configuration packets and login packet
     *     together, in the order they were received
     * @param registries the registries the game-phase codec encodes against. These must be the set
     *     the <em>viewer's client</em> built from the replayed configuration, not the viewer's own
     *     server's: the ids in the target's packets are resolved against the target server's
     *     registries, and the client has just been handed exactly those. Supplied lazily because
     *     they do not exist until the client finishes configuration.
     */
    public HostDriver(Connection connection, List<Packet<?>> handshake,
                      Supplier<RegistryAccess> registries) {
        this.connection = connection;
        this.handshake = handshake;
        this.registries = registries;
    }

    /** True once the client is in the game phase and live packets can be sent. */
    public boolean isInGame() {
        return inGame;
    }

    /**
     * Attaches the login listener and begins the sequence. The client has already connected and is
     * about to send its Hello.
     */
    public void begin() {
        connection.setupInboundProtocol(
                LoginProtocols.SERVERBOUND,
                new HostLoginListener(connection, profile -> enterConfiguration()));
        connection.setupOutboundProtocol(LoginProtocols.CLIENTBOUND);
    }

    /**
     * Switches to the configuration phase and replays the target's registries.
     *
     * <p>Outbound first: the client has acknowledged login, so from here the next thing it reads
     * must be a configuration packet.
     */
    private void enterConfiguration() {
        List<Packet<?>> configuration = new ArrayList<>();
        for (Packet<?> packet : handshake) {
            // The login packet is a game-phase packet. It was recorded with the rest but belongs
            // after the phase switch, so it is held back for enterGame().
            if (!(packet instanceof ClientboundLoginPacket)) {
                configuration.add(packet);
            }
        }
        HostConfigurationListener listener =
                new HostConfigurationListener(connection, configuration, c -> enterGame());
        connection.setupOutboundProtocol(ConfigurationProtocols.CLIENTBOUND);
        connection.setupInboundProtocol(ConfigurationProtocols.SERVERBOUND, listener);
        listener.begin();
    }

    /** Switches to the game phase and sends the target's login packet. */
    private void enterGame() {
        RegistryAccess access = registries.get();
        if (access == null) {
            SpectateClient.LOGGER.warn("spectate host: no registries after configuration");
            connection.disconnect(net.minecraft.network.chat.Component.literal(
                    "Spectate: registry sync failed"));
            return;
        }
        connection.setupOutboundProtocol(
                GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                        RegistryFriendlyByteBuf.decorator(access)));
        ClientboundLoginPacket login = findLogin();
        if (login == null) {
            SpectateClient.LOGGER.warn("spectate host: no login packet recorded; cannot enter game");
            connection.disconnect(net.minecraft.network.chat.Component.literal(
                    "Spectate: target handshake incomplete"));
            return;
        }
        connection.send(login);
        inGame = true;
        SpectateClient.LOGGER.info("spectate host: viewer entered game phase");
    }

    private ClientboundLoginPacket findLogin() {
        for (Packet<?> packet : handshake) {
            if (packet instanceof ClientboundLoginPacket login) {
                return login;
            }
        }
        return null;
    }
}
