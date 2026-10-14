package dev.spog.spectaterelay.admin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Every player who has ever authenticated to the spectate relay, persisted to {@code seen.json}
 * for the "Unique Players (All Time)" figure.
 *
 * <p>Deliberately separate from the main relay's {@code StatsStore} even though the two do the
 * same job: they are different processes with different data directories, and a player who uses
 * Relay has not necessarily ever used Spegtate. Sharing one file would conflate "has Relay" with
 * "has the spectate addon", and the two services are split precisely so neither depends on the
 * other being up.
 *
 * <p>UUIDs rather than a counter, because "unique" is only meaningful if repeat visitors can be
 * recognised -- this mod reconnects on every relay restart and network blip, so a per-connection
 * increment would count reconnects rather than people.
 *
 * <p>Recorded <b>before</b> the whitelist check, so the figure answers "how many people have this
 * mod" rather than "how many were let in". Someone who is refused still installed it, and for a
 * private mod that number is the more useful of the two.
 *
 * <p>The file is written only when a genuinely new player appears, so a busy relay does not
 * rewrite it constantly. Counting starts from whenever this version was deployed; players seen
 * before that are not retroactively known.
 */
public final class SeenStore {
    private static final Logger LOG = LoggerFactory.getLogger(SeenStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** On-disk shape. A wrapper object so fields can be added later without breaking the format. */
    static final class Data {
        Set<String> seen = new LinkedHashSet<>();
    }

    private final Path file;
    private final Set<UUID> seen = new LinkedHashSet<>();

    private SeenStore(Path file) {
        this.file = file;
    }

    public static SeenStore load(Path file) {
        SeenStore store = new SeenStore(file);
        if (!Files.exists(file)) {
            return store;
        }
        try {
            Data data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            if (data != null && data.seen != null) {
                for (String s : data.seen) {
                    try {
                        store.seen.add(UUID.fromString(s));
                    } catch (IllegalArgumentException ignored) {
                        // Skip a corrupt id rather than discarding the whole history.
                    }
                }
            }
            LOG.info("loaded {} unique player(s) from {}", store.seen.size(), file.getFileName());
        } catch (IOException | JsonSyntaxException e) {
            LOG.warn("could not read {} ({}); unique-player count restarts from zero", file, e);
        }
        return store;
    }

    /** Record a player who just authenticated. Writes the file only if they are new. */
    public synchronized void record(UUID player) {
        if (player == null || !seen.add(player)) {
            return;
        }
        save();
    }

    /** How many distinct players have authenticated since this counter began. */
    public synchronized int uniqueCount() {
        return seen.size();
    }

    private void save() {
        try {
            Data data = new Data();
            for (UUID id : seen) {
                data.seen.add(id.toString());
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(data) + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.error("could not write {}: {}", file, e.toString());
        }
    }
}
