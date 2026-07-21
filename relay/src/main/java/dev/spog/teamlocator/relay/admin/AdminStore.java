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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The relay's administrators, read once at startup from {@code admins.json}.
 *
 * <p>Identity is the <b>UUID</b>, never the name: a name can be changed, which would otherwise let
 * someone lose admin by renaming — or inherit it by taking a former admin's name. The {@code name}
 * field in the file is a human-readable comment for whoever edits it and is never used for
 * authorization.
 *
 * <p>Deliberately <b>not</b> reloadable at runtime. Admin is the one privilege escalation in the
 * system, so granting it takes a file edit plus a restart — an action that already requires server
 * access — rather than anything reachable over the network.
 *
 * <p>File format:
 * <pre>{@code
 * [
 *   { "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "name": "Notch" }
 * ]
 * }</pre>
 */
public final class AdminStore {
    private static final Logger LOG = LoggerFactory.getLogger(AdminStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Written into {@code admins.json} the first time a relay starts without one, so a fresh
     * deployment has working administrators instead of locking everyone — including its operator —
     * out of {@code /relay}.
     *
     * <p>These are a <em>seed</em>, not a hardcoded grant: authorization always reads the file, so
     * removing an entry here is a file edit rather than a rebuild, and an existing
     * {@code admins.json} is never touched.
     */
    private static final Entry[] DEFAULT_ADMINS = {
            new Entry("404c6565-ba27-41bb-8b25-7df3755be61f", "Spoginator"),
            new Entry("3b6fb9c6-4d60-4a17-80a8-966ade749601", "xqxz"),
    };

    /** One entry as it appears on disk. */
    static final class Entry {
        String uuid;
        String name;

        Entry() {
        }

        Entry(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    private final Set<UUID> admins;

    private AdminStore(Set<UUID> admins) {
        this.admins = admins;
    }

    /**
     * Load the admin list. A missing file means "no admins" and is written out as an empty list so
     * the operator has something to edit; a malformed file is refused loudly rather than silently
     * treated as empty, because quietly dropping every admin is worse than starting with none.
     */
    public static AdminStore load(Path file) {
        Set<UUID> out = new LinkedHashSet<>();
        if (!Files.exists(file)) {
            // Seed a first-run file rather than an empty one: a relay with no admins cannot be
            // administered at all, and the operator would have to hand-write JSON to fix that.
            for (Entry e : DEFAULT_ADMINS) {
                out.add(UUID.fromString(e.uuid));
            }
            try {
                Files.writeString(file, GSON.toJson(DEFAULT_ADMINS) + "\n", StandardCharsets.UTF_8);
                LOG.info("created {} with {} default admin(s); edit it to change who can moderate",
                        file.getFileName(), out.size());
            } catch (IOException e) {
                // The defaults still apply for this run; they just will not persist, so say so
                // plainly rather than letting the next restart silently drop them.
                LOG.warn("could not create {} ({}); using default admins for this run only",
                        file, e.toString());
            }
            return new AdminStore(out);
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
                        out.add(UUID.fromString(e.uuid.trim()));
                    } catch (IllegalArgumentException ex) {
                        LOG.warn("skipping malformed admin uuid '{}' in {}", e.uuid, file.getFileName());
                    }
                }
            }
            LOG.info("loaded {} admin(s) from {}", out.size(), file.getFileName());
        } catch (IOException | JsonSyntaxException e) {
            LOG.error("could not read {} ({}); starting with no admins", file, e.toString());
        }
        return new AdminStore(out);
    }

    public boolean isAdmin(UUID player) {
        return player != null && admins.contains(player);
    }

    /** Snapshot of the admin UUIDs, for the blacklist's "never ban an admin" check. */
    public List<UUID> all() {
        return new ArrayList<>(admins);
    }

    public int size() {
        return admins.size();
    }
}
