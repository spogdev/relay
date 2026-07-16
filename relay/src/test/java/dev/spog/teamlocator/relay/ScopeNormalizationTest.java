package dev.spog.teamlocator.relay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Scope keying, which decides who shares a Minecraft server with whom.
 *
 * <p>Two players on one server must produce one scope: the relay files positions under the scope
 * and never routes across them, so a mismatch makes teammates silently invisible to each other —
 * connected, no error, no log line, just nobody there.
 *
 * <p>These exist because that shipped. The key used to be the address the player typed, so a player
 * dialling a server by hostname and one dialling it by IP landed in different scopes. It hid on
 * ordinary servers, where {@code :25565} was stripped and everyone converged on the bare hostname
 * anyway, and only surfaced on a shared host whose servers live on non-default ports.
 */
class ScopeNormalizationTest {

    @Test
    void caseAndWhitespaceDoNotSplitAScope() {
        assertEquals(RelayServer.normalizeScope("93.158.202.128:25358"),
                RelayServer.normalizeScope("  93.158.202.128:25358  "));
        assertEquals(RelayServer.normalizeScope("93.158.202.128:25358"),
                RelayServer.normalizeScope("93.158.202.128:25358"));
        // IPv6 hex is case-insensitive; two clients may format it either way.
        assertEquals(RelayServer.normalizeScope("[2A0E:CB01:6B:3000::1]:25358"),
                RelayServer.normalizeScope("[2a0e:cb01:6b:3000::1]:25358"));
    }

    @Test
    void theDefaultPortIsNoLongerStripped() {
        // The client now sends a resolved ip:port, where the port is always explicit. Stripping
        // :25565 here would drop it on one side only and split the scope in half — the exact
        // failure this keying replaced.
        assertEquals("1.2.3.4:25565", RelayServer.normalizeScope("1.2.3.4:25565"));
    }

    @Test
    void portsSeparateServersSharingOneBox() {
        // Shared hosts put unrelated servers on one machine, distinguished only by port. Merging
        // them would leak positions between strangers, so these must stay distinct.
        assertNotEquals(RelayServer.normalizeScope("93.158.202.128:25358"),
                RelayServer.normalizeScope("93.158.202.128:25970"));
    }

    @Test
    void aBlankScopeIsNotSilentlySharedWithEveryOtherBlankOne() {
        // "unknown" is a real bucket, so keep it explicit and stable rather than null-ish.
        assertEquals("unknown", RelayServer.normalizeScope(null));
        assertEquals("unknown", RelayServer.normalizeScope(""));
        assertEquals("unknown", RelayServer.normalizeScope("   "));
    }
}
