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
 *
 * <p>Moderation lives in the SpogStats dashboard. Give the relay project's secret with
 * {@code --dashboard-secret} or the {@code RELAY_DASHBOARD_SECRET} environment variable (the
 * environment keeps it out of {@code ps}); without one the relay runs open to everyone.
 */
public final class RelayMain {
    private static final Logger LOG = LoggerFactory.getLogger(RelayMain.class);

    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_DASHBOARD = "https://dash.spog.dev/";

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        String host = "0.0.0.0";
        // The dashboard rules cache lives here; defaults to the working directory so the systemd
        // unit's WorkingDirectory decides, with --data-dir to override.
        java.nio.file.Path dataDir = java.nio.file.Path.of(".");
        String dashboardUrl = DEFAULT_DASHBOARD;
        String dashboardSecret = System.getenv("RELAY_DASHBOARD_SECRET");

        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                case "--data-dir" -> dataDir = java.nio.file.Path.of(args[++i]);
                case "--dashboard-url" -> dashboardUrl = args[++i];
                case "--dashboard-secret" -> dashboardSecret = args[++i];
                default -> { }
            }
        }

        RelayServer server = new RelayServer(new InetSocketAddress(host, port));
        if (dashboardSecret == null || dashboardSecret.isBlank()) {
            LOG.warn("No dashboard secret set: the relay is open to everyone and has no moderation. "
                    + "Set RELAY_DASHBOARD_SECRET to the relay project's secret from the dashboard.");
        } else {
            server.useDashboard(Dashboard.link(java.net.URI.create(dashboardUrl), dashboardSecret,
                    dataDir.resolve("dashboard-cache.json"), server::connectedPlayers, server::kickDenied));
            LOG.info("Moderation from the dashboard at {}", dashboardUrl);
        }
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
