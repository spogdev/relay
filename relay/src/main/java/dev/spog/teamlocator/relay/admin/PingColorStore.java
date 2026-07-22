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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Administrator overrides for players' ping colours, persisted to {@code pingcolors.json}.
 *
 * <p>An override replaces the player's own choice entirely: the relay stamps it onto every ping
 * that player sends, whatever colour their client asked for. Unlike a player's own palette (five
 * values derived from their UUID) an override may be any well-formed colour, since an administrator
 * setting it is the authority.
 *
 * <p>Written on every change like the blacklist, so an override survives a restart.
 */
public final class PingColorStore {
    private static final Logger LOG = LoggerFactory.getLogger(PingColorStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One entry as it appears on disk. */
    static final class Entry {
        String uuid;
        String name;
        String color;
        String setBy;

        Entry() {
        }

        Entry(String uuid, String name, String color, String setBy) {
            this.uuid = uuid;
            this.name = name;
            this.color = color;
            this.setBy = setBy;
        }
    }

    private final Path file;
    private final Map<UUID, Entry> overrides = new LinkedHashMap<>();

    private PingColorStore(Path file) {
        this.file = file;
    }

    public static PingColorStore load(Path file) {
        PingColorStore store = new PingColorStore(file);
        if (!Files.exists(file)) {
            return store;
        }
        try {
            Entry[] entries = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    Entry[].class);
            if (entries != null) {
                for (Entry e : entries) {
                    if (e == null || e.uuid == null || e.color == null) {
                        continue;
                    }
                    try {
                        store.overrides.put(UUID.fromString(e.uuid.trim()), e);
                    } catch (IllegalArgumentException ex) {
                        LOG.warn("skipping malformed ping-colour uuid '{}'", e.uuid);
                    }
                }
            }
            LOG.info("loaded {} ping-colour override(s)", store.overrides.size());
        } catch (IOException | JsonSyntaxException e) {
            LOG.error("could not read {} ({}); starting with no overrides", file, e.toString());
        }
        return store;
    }

    /** The override for this player, or null if they use their own palette. */
    public synchronized String get(UUID player) {
        Entry e = overrides.get(player);
        return e == null ? null : e.color;
    }

    /** Set an override. Replaces any existing one for that player. */
    public synchronized void set(UUID player, String name, String color, String setBy) {
        overrides.put(player, new Entry(player.toString(), name, color, setBy));
        save();
    }

    /** Remove an override. Returns false if there wasn't one. */
    public synchronized boolean remove(UUID player) {
        if (overrides.remove(player) == null) {
            return false;
        }
        save();
        return true;
    }

    public synchronized int size() {
        return overrides.size();
    }

    /** Temp file plus atomic-where-supported move, so a crash cannot truncate the overrides. */
    private void save() {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(overrides.values()) + "\n", StandardCharsets.UTF_8);
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
