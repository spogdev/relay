package dev.spog.spectaterelay;

import dev.spog.spectaterelay.admin.SpectateAdminService;
import dev.spog.teamlocator.relay.auth.MojangVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Entrypoint for the spectate relay. Runs alongside the main relay, on its own port:
 *
 * <pre>
 *   java -jar relay-spectate-service-all.jar --port 8081
 * </pre>
 *
 * <p>Put a TLS-terminating reverse proxy in front for {@code wss://}, as with the main relay.
 *
 * <p>Separate process by design: this service forwards terrain, which is orders of magnitude more
 * traffic than coordinates, so its failure modes are kept away from position and alert routing.
 */
public final class SpectateRelayMain {
    private static final Logger LOG = LoggerFactory.getLogger(SpectateRelayMain.class);

    private static final int DEFAULT_PORT = 8081;

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        String host = "0.0.0.0";
        // Defaults to the working directory, so the usual deployment -- both services started
        // from the same folder on the VPS -- shares one admins.json with the main relay.
        String dataDir = ".";

        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                case "--data" -> dataDir = args[++i];
                default -> { }
            }
        }

        SpectateAdminService admin =
                SpectateAdminService.load(java.nio.file.Path.of(dataDir));
        SpectateRelayServer server = new SpectateRelayServer(
                new InetSocketAddress(host, port), new MojangVerifier(), admin);
        // A streaming target sends continuously; a viewer that has gone quiet for this long is
        // half-open rather than idle.
        server.setConnectionLostTimeout(60);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("shutting down spectate relay");
            try {
                server.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        server.run();
    }
}
