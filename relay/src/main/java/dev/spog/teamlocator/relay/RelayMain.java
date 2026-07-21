package dev.spog.teamlocator.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Entrypoint for the standalone relay. Run on any small VPS:
 *
 * <pre>
 *   java -jar teamlocator-relay-all.jar --port 8080
 * </pre>
 *
 * Put a TLS-terminating reverse proxy (e.g. Caddy) in front for {@code wss://}; see the README.
 */
public final class RelayMain {
    private static final Logger LOG = LoggerFactory.getLogger(RelayMain.class);

    private static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        String host = "0.0.0.0";
        // admins.json / blacklist.json live here; defaults to the working directory so the systemd
        // unit's WorkingDirectory decides, with --data-dir to override.
        java.nio.file.Path dataDir = java.nio.file.Path.of(".");

        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                case "--data-dir" -> dataDir = java.nio.file.Path.of(args[++i]);
                default -> { }
            }
        }

        AdminService admins = AdminService.load(dataDir);
        RelayServer server = new RelayServer(new InetSocketAddress(host, port), admins);
        // Drop idle/half-open sockets; clients send position updates continuously while in-game.
        server.setConnectionLostTimeout(60);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("shutting down relay");
            try {
                server.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        server.run();
    }
}
