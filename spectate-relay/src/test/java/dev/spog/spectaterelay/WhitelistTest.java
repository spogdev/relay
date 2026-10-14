package dev.spog.spectaterelay;

import dev.spog.spectaterelay.admin.SeenStore;
import dev.spog.spectaterelay.admin.SpectateAdminService;
import dev.spog.spectaterelay.admin.WhitelistState;
import dev.spog.spectaterelay.admin.WhitelistStore;
import dev.spog.teamlocator.relay.admin.AdminStore;
import dev.spog.teamlocator.relay.admin.NameResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Access control for the private mod: deny by default, and the rules that keep an operator from
 * locking themselves out.
 *
 * <p>No network and no disk beyond a temp directory: names resolve through a stub, so the suite
 * does not depend on Mojang being up or on a live account existing.
 */
final class WhitelistTest {
    private static final UUID ADMIN = UUID.fromString("404c6565-ba27-41bb-8b25-7df3755be61f");
    private static final UUID ALLOWED = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STRANGER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** Resolves only the names it was given; everything else is "no such account". */
    private static final class StubNames implements NameResolver {
        private final Map<String, UUID> known = new HashMap<>();
        private boolean down;

        StubNames with(String name, UUID id) {
            known.put(name.toLowerCase(java.util.Locale.ROOT), id);
            return this;
        }

        @Override
        public UUID resolve(String name) throws Unavailable {
            if (down) {
                throw new Unavailable(new IOException("stub offline"));
            }
            return known.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** A live state with nobody online, for the commands that do not consult it. */
    private static SpectateAdminService.LiveState empty() {
        return new SpectateAdminService.LiveState() {
            @Override
            public Optional<UUID> byName(String name) {
                return Optional.empty();
            }

            @Override
            public int onlineCount() {
                return 0;
            }

            @Override
            public int spectatingCount() {
                return 0;
            }

            @Override
            public List<String> onlineNames() {
                return List.of();
            }
        };
    }

    private static SpectateAdminService service(Path dir, StubNames names) throws IOException {
        Files.writeString(dir.resolve("admins.json"),
                "[{\"uuid\":\"" + ADMIN + "\",\"name\":\"Spoginator\"}]", StandardCharsets.UTF_8);
        return SpectateAdminService.of(
                AdminStore.load(dir.resolve("admins.json")),
                WhitelistStore.load(dir.resolve("whitelist.json")),
                WhitelistState.load(dir.resolve("whitelist-state.json")),
                SeenStore.load(dir.resolve("seen.json")),
                names);
    }

    @Test
    void everyoneIsDeniedByDefault(@TempDir Path dir) throws IOException {
        SpectateAdminService s = service(dir, new StubNames());
        assertFalse(s.mayConnect(STRANGER), "an unlisted player must be refused");
    }

    @Test
    void anAdminMayAlwaysConnect(@TempDir Path dir) throws IOException {
        // The escape hatch: an empty or corrupt whitelist must not lock out the people who can
        // fix it, or the only remedy would be console access to the VPS.
        SpectateAdminService s = service(dir, new StubNames());
        assertTrue(s.mayConnect(ADMIN));
    }

    @Test
    void aWhitelistedPlayerMayConnect(@TempDir Path dir) throws IOException {
        SpectateAdminService s = service(dir, new StubNames().with("Ally", ALLOWED));
        s.execute(ADMIN, "Spoginator", "whitelist", List.of("add", "Ally"), empty());
        assertTrue(s.mayConnect(ALLOWED));
    }

    @Test
    void aNonAdminCannotRunCommands(@TempDir Path dir) throws IOException {
        SpectateAdminService s = service(dir, new StubNames().with("Ally", ALLOWED));
        SpectateAdminService.Result r =
                s.execute(STRANGER, "Stranger", "whitelist", List.of("add", "Ally"), empty());
        assertTrue(r.error());
        assertFalse(s.mayConnect(ALLOWED), "the add must not have taken effect");
    }

    @Test
    void theListSurvivesARestart(@TempDir Path dir) throws IOException {
        SpectateAdminService first = service(dir, new StubNames().with("Ally", ALLOWED));
        first.execute(ADMIN, "Spoginator", "whitelist", List.of("add", "Ally"), empty());

        // A second service over the same directory is exactly what a restart is.
        SpectateAdminService second = service(dir, new StubNames().with("Ally", ALLOWED));
        assertTrue(second.mayConnect(ALLOWED), "the whitelist must persist across restarts");
    }

    @Test
    void disablingLetsAnyoneInButKeepsTheList(@TempDir Path dir) throws IOException {
        SpectateAdminService s = service(dir, new StubNames().with("Ally", ALLOWED));
        s.execute(ADMIN, "Spoginator", "whitelist", List.of("add", "Ally"), empty());
        s.execute(ADMIN, "Spoginator", "whitelist", List.of("off"), empty());

        assertTrue(s.mayConnect(STRANGER), "with the whitelist off, anyone may connect");

        s.execute(ADMIN, "Spoginator", "whitelist", List.of("on"), empty());
        assertFalse(s.mayConnect(STRANGER), "turning it back on restores enforcement");
        assertTrue(s.mayConnect(ALLOWED), "and the list itself was retained");
    }

    @Test
    void theEnabledFlagSurvivesARestart(@TempDir Path dir) throws IOException {
        service(dir, new StubNames()).execute(
                ADMIN, "Spoginator", "whitelist", List.of("off"), empty());
        assertTrue(service(dir, new StubNames()).mayConnect(STRANGER),
                "enforcement must still be off after a restart");
    }

    @Test
    void removingRevokesAccess(@TempDir Path dir) throws IOException {
        StubNames names = new StubNames().with("Ally", ALLOWED);
        SpectateAdminService s = service(dir, names);
        s.execute(ADMIN, "Spoginator", "whitelist", List.of("add", "Ally"), empty());
        s.execute(ADMIN, "Spoginator", "whitelist", List.of("remove", "Ally"), empty());
        assertFalse(s.mayConnect(ALLOWED));
    }

    @Test
    void accessCanBeRevokedWhileMojangIsDown(@TempDir Path dir) throws IOException {
        // Removal is the safety-critical direction: being unable to revoke during an outage is
        // worse than being unable to grant, so remove falls back to the stored names.
        StubNames names = new StubNames().with("Ally", ALLOWED);
        SpectateAdminService s = service(dir, names);
        s.execute(ADMIN, "Spoginator", "whitelist", List.of("add", "Ally"), empty());

        names.down = true;
        SpectateAdminService.Result r =
                s.execute(ADMIN, "Spoginator", "whitelist", List.of("remove", "Ally"), empty());
        assertFalse(r.error(), "should fall back to the recorded name: " + r.lines());
        assertFalse(s.mayConnect(ALLOWED));
    }

    @Test
    void anOutageIsNotReportedAsAnUnknownPlayer(@TempDir Path dir) throws IOException {
        StubNames names = new StubNames().with("Ally", ALLOWED);
        names.down = true;
        SpectateAdminService s = service(dir, names);
        SpectateAdminService.Result r =
                s.execute(ADMIN, "Spoginator", "whitelist", List.of("add", "Ally"), empty());
        assertTrue(r.error());
        assertTrue(String.join(" ", r.lines()).contains("Try again"),
                "an outage must be distinguishable from a typo: " + r.lines());
    }

    @Test
    void aRenameDoesNotLoseAccess(@TempDir Path dir) throws IOException {
        // Identity is the UUID: the whole point of resolving the name once and storing the id.
        SpectateAdminService s = service(dir, new StubNames().with("OldName", ALLOWED));
        s.execute(ADMIN, "Spoginator", "whitelist", List.of("add", "OldName"), empty());
        assertTrue(s.mayConnect(ALLOWED), "access follows the account, not the name");
    }

    @Test
    void theUniqueCountIgnoresReconnects(@TempDir Path dir) throws IOException {
        SpectateAdminService s = service(dir, new StubNames());
        s.recordSeen(ALLOWED);
        s.recordSeen(ALLOWED);
        s.recordSeen(STRANGER);
        SpectateAdminService.Result r = s.execute(ADMIN, "Spoginator", "stats", List.of(), empty());
        assertTrue(r.lines().contains("Unique Players (All Time): 2"),
                "two distinct players seen three times: " + r.lines());
    }

    @Test
    void statsReportsTheLiveFigures(@TempDir Path dir) throws IOException {
        SpectateAdminService s = service(dir, new StubNames());
        SpectateAdminService.Result r = s.execute(ADMIN, "Spoginator", "stats", List.of(),
                new SpectateAdminService.LiveState() {
                    @Override
                    public Optional<UUID> byName(String name) {
                        return Optional.empty();
                    }

                    @Override
                    public int onlineCount() {
                        return 7;
                    }

                    @Override
                    public int spectatingCount() {
                        return 3;
                    }

                    @Override
                    public List<String> onlineNames() {
                        return List.of("a", "b");
                    }
                });
        assertEquals("Online Players: 7", r.lines().get(0));
        assertTrue(r.lines().contains("Currently Spectating: 3"), r.lines().toString());
    }

    @Test
    void listSaysWhetherEnforcementIsOn(@TempDir Path dir) throws IOException {
        // The list is retained while disabled, so a populated list with enforcement off would
        // otherwise read as though those people had access when they do not need it.
        SpectateAdminService s = service(dir, new StubNames());
        assertTrue(String.join(" ",
                        s.execute(ADMIN, "Spoginator", "whitelist", List.of("list"), empty()).lines())
                .contains("enforcement is on"));
        s.execute(ADMIN, "Spoginator", "whitelist", List.of("off"), empty());
        assertTrue(String.join(" ",
                        s.execute(ADMIN, "Spoginator", "whitelist", List.of("list"), empty()).lines())
                .contains("enforcement is off"));
    }

    @Test
    void aCorruptWhitelistStillLetsAdminsIn(@TempDir Path dir) throws IOException {
        // The recovery path: a truncated file must not need console access to fix.
        Files.writeString(dir.resolve("whitelist.json"), "{ this is not json",
                StandardCharsets.UTF_8);
        SpectateAdminService s = service(dir, new StubNames());
        assertTrue(s.mayConnect(ADMIN));
        assertFalse(s.mayConnect(STRANGER));
    }
}
