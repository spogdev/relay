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
import java.util.Optional;
import java.util.UUID;

/**
 * Players barred from the relay, keyed by UUID and persisted to {@code blacklist.json} on every
 * change so a ban survives a restart.
 *
 * <p>Like {@link AdminStore} the name is only a comment — enforcement is entirely by UUID, so a
 * banned player cannot escape by renaming. Unlike the admin list this <em>is</em> mutated at
 * runtime, by {@code /relay blacklist|whitelist}, and every mutation writes the file immediately:
 * a ban that vanished on the next restart would be worse than no ban at all.
 *
 * <p>Thread safety: mutations are serialised on this object, so a concurrent ban and unban cannot
 * interleave into a half-written file. Reads are on a synchronized snapshot; the map is small
 * (a ban list, not a session table) so this costs nothing meaningful on the hot path.
 */
public final class BlacklistStore {
    private static final Logger LOG = LoggerFactory.getLogger(BlacklistStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One entry as it appears on disk. */
    static final class Entry {
        String uuid;
        String name;
        String bannedBy;

        Entry() {
        }

        Entry(String uuid, String name, String bannedBy) {
            this.uuid = uuid;
            this.name = name;
            this.bannedBy = bannedBy;
        }
    }

    private final Path file;
    private final Map<UUID, Entry> banned = new LinkedHashMap<>();

    private BlacklistStore(Path file) {
        this.file = file;
    }

    /**
     * Load the blacklist. A missing file is simply an empty list (and is created on the first ban);
     * a malformed one is logged and treated as empty, since refusing to boot over a corrupt ban list
     * would take the whole relay down for everyone.
     */
    public static BlacklistStore load(Path file) {
        BlacklistStore store = new BlacklistStore(file);
        if (!Files.exists(file)) {
            return store;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Entry[] entries = GSON.fromJson(json, Entry[].class);
            if (entries != null) {
                for (Entry e : entries) {
                    if (e == null || e.uuid == null || e.uuid.isBlank()) {
                        continue;
                    }
                    try {
                        store.banned.put(UUID.fromString(e.uuid.trim()), e);
                    } catch (IllegalArgumentException ex) {
                        LOG.warn("skipping malformed blacklist uuid '{}'", e.uuid);
                    }
                }
            }
            LOG.info("loaded {} blacklisted player(s)", store.banned.size());
        } catch (IOException | JsonSyntaxException e) {
            LOG.error("could not read {} ({}); starting with an empty blacklist", file, e.toString());
        }
        return store;
    }

    public synchronized boolean isBanned(UUID player) {
        return player != null && banned.containsKey(player);
    }

    /** The stored display name for a banned player, if one was recorded. */
    public synchronized Optional<String> nameOf(UUID player) {
        Entry e = banned.get(player);
        return Optional.ofNullable(e == null ? null : e.name);
    }

    /**
     * Ban a player. Returns false if they were already banned (so the caller can say so rather than
     * reporting a no-op as success).
     */
    public synchronized boolean add(UUID player, String name, String bannedBy) {
        if (banned.containsKey(player)) {
            return false;
        }
        banned.put(player, new Entry(player.toString(), name, bannedBy));
        save();
        return true;
    }

    /** Unban a player. Returns false if they were not banned. */
    public synchronized boolean remove(UUID player) {
        if (banned.remove(player) == null) {
            return false;
        }
        save();
        return true;
    }

    public synchronized int size() {
        return banned.size();
    }

    /**
     * Write the current list. Via a temporary file and an atomic-where-supported move, so a crash
     * mid-write cannot leave a truncated blacklist that silently unbans people on next boot.
     */
    private void save() {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(banned.values()) + "\n", StandardCharsets.UTF_8);
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
