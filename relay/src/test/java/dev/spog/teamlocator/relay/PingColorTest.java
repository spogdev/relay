package dev.spog.teamlocator.relay;

import dev.spog.teamlocator.relay.protocol.PingColors;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ping palette and, more importantly, the rule that a client cannot choose a colour outside it.
 *
 * <p>The colour ends up rendered on every teammate's screen, so "the client asked for it" is not a
 * good enough reason to send it: a patched client must not be able to pick a colour that isn't
 * theirs, and an administrator's override must beat whatever the client wants.
 */
class PingColorTest {
    private static final UUID PLAYER = UUID.fromString("404c6565-ba27-41bb-8b25-7df3755be61f");
    private static final UUID OTHER = UUID.fromString("3b6fb9c6-4d60-4a17-80a8-966ade749601");

    @Test
    void fiveColoursComeFromTheFirstThirtyHexCharactersOfTheUuid() {
        List<String> colours = PingColors.forPlayer(PLAYER);
        assertEquals(5, colours.size());
        assertEquals(List.of("#404c65", "#65ba27", "#41bb8b", "#257df3", "#755be6"), colours,
                "the slices must be chars 0-5, 6-11, 12-17, 18-23, 24-29 of the dashless uuid");
        for (String c : colours) {
            assertTrue(PingColors.isWellFormed(c), c + " should be a well-formed colour");
        }
    }

    @Test
    void differentPlayersGetDifferentPalettes() {
        assertNotEquals(PingColors.forPlayer(PLAYER), PingColors.forPlayer(OTHER));
    }

    @Test
    void anOutOfRangeIndexFallsBackToTheFirstColour() {
        // A corrupt or hand-edited client config must still yield a usable colour.
        assertEquals("#404c65", PingColors.forPlayer(PLAYER, -1));
        assertEquals("#404c65", PingColors.forPlayer(PLAYER, 99));
        assertEquals("#65ba27", PingColors.forPlayer(PLAYER, 1));
    }

    @Test
    void onlyAPlayersOwnColoursValidateForThem() {
        assertTrue(PingColors.isValidFor(PLAYER, "#65ba27"));
        assertTrue(PingColors.isValidFor(PLAYER, "#65BA27"), "matching should be case-insensitive");
        // Another player's colour is not yours to use.
        assertFalse(PingColors.isValidFor(PLAYER, "#c64d60"));
        assertFalse(PingColors.isValidFor(PLAYER, "#ff0000"));
        assertFalse(PingColors.isValidFor(PLAYER, "not a colour"));
        assertFalse(PingColors.isValidFor(PLAYER, null));
    }

    @Test
    void malformedColoursAreRejectedByTheFormatCheck() {
        assertFalse(PingColors.isWellFormed(null));
        assertFalse(PingColors.isWellFormed(""));
        assertFalse(PingColors.isWellFormed("404c65"), "missing the leading #");
        assertFalse(PingColors.isWellFormed("#404c6"), "too short");
        assertFalse(PingColors.isWellFormed("#404c655"), "too long");
        assertFalse(PingColors.isWellFormed("#40zc65"), "z is not hex");
        assertTrue(PingColors.isWellFormed("#ABCDEF"), "uppercase hex is fine");
    }

    @Test
    void aClientCannotSendAColourThatIsNotItsOwn() {
        // The heart of the sanity check: an invented colour is replaced with the player's first,
        // never passed through to viewers.
        assertEquals("#404c65", RelayRouter.resolvePingColor(PLAYER, "#ff0000", null));
        assertEquals("#404c65", RelayRouter.resolvePingColor(PLAYER, "#c64d60", null),
                "another player's colour must not be usable");
        assertEquals("#404c65", RelayRouter.resolvePingColor(PLAYER, "garbage", null));
        assertEquals("#404c65", RelayRouter.resolvePingColor(PLAYER, null, null));
    }

    @Test
    void aValidChoiceIsPassedThroughUnchanged() {
        assertEquals("#257df3", RelayRouter.resolvePingColor(PLAYER, "#257df3", null));
        assertEquals("#257df3", RelayRouter.resolvePingColor(PLAYER, "#257DF3", null),
                "a valid choice should normalize rather than be rejected");
    }

    @Test
    void anAdministratorOverrideBeatsWhateverTheClientAsksFor() {
        // Including a colour the player legitimately owns: the override is the point.
        assertEquals("#ff0000", RelayRouter.resolvePingColor(PLAYER, "#65ba27", "#ff0000"));
        assertEquals("#ff0000", RelayRouter.resolvePingColor(PLAYER, "#ffffff", "#FF0000"),
                "an override should normalize too");
    }

    @Test
    void aMalformedOverrideIsIgnoredRatherThanRendered() {
        // A corrupt pingcolors.json must not put junk into every viewer's renderer; fall back to
        // the normal rules instead.
        assertEquals("#65ba27", RelayRouter.resolvePingColor(PLAYER, "#65ba27", "not-a-colour"));
        assertEquals("#404c65", RelayRouter.resolvePingColor(PLAYER, "#ff0000", "#zzz"));
    }
}
