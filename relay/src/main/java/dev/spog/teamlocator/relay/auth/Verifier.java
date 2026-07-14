package dev.spog.teamlocator.relay.auth;

import java.net.InetAddress;
import java.util.UUID;

/**
 * Identity verification for a connecting client. Extracted as an interface so the relay's routing
 * can be exercised in tests without live Mojang accounts; production always uses
 * {@link MojangVerifier}.
 */
public interface Verifier {
    /** Thrown when the identity provider can't be reached; the caller should tell the client to retry. */
    final class Unavailable extends Exception {
        public Unavailable(Throwable cause) {
            super(cause);
        }
    }

    /** A fresh single-use nonce handed to a connecting client as the joinServer {@code serverId}. */
    String newChallenge();

    /**
     * Confirm that {@code profileName} really completed the join for {@code serverId}.
     *
     * @return the verified UUID, or {@code null} if the identity provider did not confirm it
     */
    UUID verify(String profileName, String serverId, InetAddress clientIp) throws Unavailable;
}
