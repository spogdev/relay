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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who is allowed to use Spegtate, persisted to {@code whitelist.json}.
 *
 * <p>This mod is private, so access is <b>deny by default</b>: a player who is not on this list is
 * refused at authentication. That inverts the main relay's {@code BlacklistStore}, where the
 * absence of an entry means "allowed" -- the two look similar and mean opposite things, which is
 * why they are separate classes rather than one with a flag.
 *
 * <p>Identity is the <b>UUID</b>, never the name, for the same reason as everywhere else in this
 * codebase: names are rented, not owned. Someone who is whitelisted keeps access after a rename,
 * and taking a whitelisted player's old name grants nothing. The {@code name} field is a comment
 * for whoever reads the file, and the {@code addedBy} field records which admin granted access.
 *
 * <p><b>The list is retained when the whitelist is switched off.</b> Disabling is a gate, not a
 * purge: {@link WhitelistState} holds the on/off flag in its own file so that turning enforcement
 * off and on again returns exactly the same set of players. Anything else would make "off" a
 * destructive operation, which is not what an operator expects from a toggle.
 *
 * <p>Every mutation writes the file immediately, through a temporary file and an
 * atomic-where-supported move: an entry that vanished on the next restart would silently lock out
 * someone who had been granted access, and a crash mid-write must not truncate the list into
 * locking out everyone.
 *
 * <p>Thread safety: mutations are serialised on this object. The list is small -- an access list,
 * not a session table -- so a synchronized read on the authentication path costs nothing
 * meaningful.
 */
public final class WhitelistStore {
    private static final Logger LOG = LoggerFactory.getLogger(WhitelistStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One entry as it appears on disk. */
    static final class Entry {
        String uuid;
        String name;
        String addedBy;

        Entry() {
        }

        Entry(String uuid, String name, String addedBy) {
            this.uuid = uuid;
            this.name = name;
            this.addedBy = addedBy;
        }
    }

    private final Path file;
    private final Map<UUID, Entry> allowed = new LinkedHashMap<>();

    private WhitelistStore(Path file) {
        this.file = file;
    }

    /**
     * Load the whitelist.
     *
     * <p>A missing file is an empty list, created on the first add. A malformed one is logged and
     * treated as empty rather than refusing to boot -- but note that for a deny-by-default list
     * "empty" locks everyone out, so the error is logged at {@code error} level and the admins
     * from {@code admins.json} always bypass the check (see
     * {@code SpectateRelayServer}), which is what keeps a corrupt file recoverable over the
     * network instead of requiring console access.
     */
    public static WhitelistStore load(Path file) {
        WhitelistStore store = new WhitelistStore(file);
        if (!Files.exists(file)) {
            LOG.info("no {} yet; only admins may connect until someone is added",
                    file.getFileName());
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
                        store.allowed.put(UUID.fromString(e.uuid.trim()), e);
                    } catch (IllegalArgumentException ex) {
                        LOG.warn("skipping malformed whitelist uuid '{}'", e.uuid);
                    }
                }
            }
            LOG.info("loaded {} whitelisted player(s)", store.allowed.size());
        } catch (IOException | JsonSyntaxException e) {
            LOG.error("could not read {} ({}); NOBODY but admins will be able to connect "
                    + "until this is fixed", file, e.toString());
        }
        return store;
    }

    public synchronized boolean isWhitelisted(UUID player) {
        return player != null && allowed.containsKey(player);
    }

    /** The stored display name for a whitelisted player, if one was recorded. */
    public synchronized Optional<String> nameOf(UUID player) {
        Entry e = allowed.get(player);
        return Optional.ofNullable(e == null ? null : e.name);
    }

    /**
     * Grant access. Returns false if they were already whitelisted, so the caller can say so
     * rather than reporting a no-op as success.
     */
    public synchronized boolean add(UUID player, String name, String addedBy) {
        if (allowed.containsKey(player)) {
            return false;
        }
        allowed.put(player, new Entry(player.toString(), name, addedBy));
        save();
        return true;
    }

    /** Revoke access. Returns false if they were not whitelisted. */
    public synchronized boolean remove(UUID player) {
        if (allowed.remove(player) == null) {
            return false;
        }
        save();
        return true;
    }

    /**
     * Everyone on the list, in the order they were added, as {@code name (uuid)} pairs.
     *
     * <p>Returns a copy: the caller formats this into chat lines on another thread, and handing
     * out the live map would race with a concurrent add.
     */
    public synchronized List<Listed> all() {
        List<Listed> out = new ArrayList<>(allowed.size());
        for (Map.Entry<UUID, Entry> e : allowed.entrySet()) {
            out.add(new Listed(e.getKey(), e.getValue().name, e.getValue().addedBy));
        }
        return out;
    }

    /** One whitelisted player, for display. */
    public record Listed(UUID uuid, String name, String addedBy) {
    }

    public synchronized int size() {
        return allowed.size();
    }

    /**
     * Write the current list. Via a temporary file and an atomic-where-supported move, so a crash
     * mid-write cannot leave a truncated whitelist -- which, for a deny-by-default list, would
     * lock out everyone it dropped.
     */
    private void save() {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(allowed.values()) + "\n", StandardCharsets.UTF_8);
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
