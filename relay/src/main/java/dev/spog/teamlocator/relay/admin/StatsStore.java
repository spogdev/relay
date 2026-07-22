package dev.spog.teamlocator.relay.admin;

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
 * Counts how many distinct players have ever authenticated to this relay, persisted to
 * {@code stats.json} so the figure survives restarts.
 *
 * <p>Stores UUIDs rather than a bare number because "unique" is only meaningful if repeat visitors
 * can be recognised — a counter incremented per connection would tally reconnects, and this mod
 * reconnects on every relay restart and network blip.
 *
 * <p>The file is written only when a genuinely new player appears, not on every connect, so a busy
 * relay does not rewrite it constantly. Counting starts from whenever this version was deployed;
 * players seen before that are not retroactively known.
 */
public final class StatsStore {
    private static final Logger LOG = LoggerFactory.getLogger(StatsStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** On-disk shape. A wrapper object so fields can be added later without breaking the format. */
    static final class Data {
        Set<String> seen = new LinkedHashSet<>();
    }

    private final Path file;
    private final Set<UUID> seen = new LinkedHashSet<>();

    private StatsStore(Path file) {
        this.file = file;
    }

    public static StatsStore load(Path file) {
        StatsStore store = new StatsStore(file);
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
                        // skip a corrupt id rather than discarding the whole history
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

    /** Write via a temp file and an atomic-where-supported move, so a crash cannot truncate it. */
    private void save() {
        Data data = new Data();
        for (UUID id : seen) {
            data.seen.add(id.toString());
        }
        try {
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
