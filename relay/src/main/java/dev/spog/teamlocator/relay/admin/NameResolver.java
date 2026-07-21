package dev.spog.teamlocator.relay.admin;

import java.util.UUID;

/**
 * Resolves a Minecraft account name to its UUID, so an admin can ban someone who is not currently
 * connected to the relay. Connected players are resolved from the session registry instead — those
 * UUIDs the relay verified itself — and this is only the fallback for everyone else.
 *
 * <p>A test seam, mirroring {@code Verifier}: the real implementation talks to Mojang, tests use a
 * stub so the suite never depends on the network or on live accounts.
 */
public interface NameResolver {
    /**
     * @return the account's UUID, or null if no such account exists
     * @throws Unavailable if the lookup service could not be reached (distinct from "no such
     *                     player", so the caller can say "try again" rather than "unknown player")
     */
    UUID resolve(String name) throws Unavailable;

    /** The name service was unreachable; the answer is unknown rather than negative. */
    class Unavailable extends Exception {
        public Unavailable(Throwable cause) {
            super(cause);
        }
    }
}
