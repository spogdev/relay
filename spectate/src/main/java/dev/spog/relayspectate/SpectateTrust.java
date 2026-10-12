package dev.spog.relayspectate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who may watch this client, and who this client may ask to watch.
 *
 * <p>This is the addon's own permission model, and the reason it exists is worth stating plainly:
 * it used to borrow Relay's position-sharing set, and that set answers a different question.
 * Sharing coordinates lets someone see a marker on a map; spectating lets them see through your
 * eyes -- your base, your inventory screen, wherever you walk. Those are not the same grant, and a
 * player who wanted the first was being signed up for the second. Keeping a separate list means
 * trusting someone on the map does not silently also admit them to the camera.
 *
 * <p>The list is symmetric in use but not in effect. {@link #allowedViewers()} is uploaded to the
 * service and is authoritative: it decides who may watch. {@link #candidates()} is only the menu's
 * guess at who might let this client watch them -- it cannot know, because the other side holds
 * that answer, so a row may be clicked and refused.
 */
public final class SpectateTrust {
    private SpectateTrust() {
    }

    /** One remembered player: a stable id, and a name cached only for display. */
    public static final class Entry {
        /** Stored as a string so a malformed config entry fails at one call site, not at load. */
        public String uuid;
        public String name;

        public Entry() {
        }

        public Entry(UUID uuid, String name) {
            this.uuid = uuid.toString();
            this.name = name;
        }

        /** @throws IllegalArgumentException if the stored id is malformed */
        public UUID id() {
            return UUID.fromString(uuid);
        }

        /** The name to show, falling back to a short id when none was ever recorded. */
        public String display() {
            if (name != null && !name.isBlank()) {
                return name;
            }
            return uuid == null || uuid.length() < 8 ? "?" : uuid.substring(0, 8);
        }
    }

    /**
     * The set uploaded to the service: who may watch this client.
     *
     * <p>Empty when {@link SpectateConfig#allowSpectators} is off, which is what makes that switch
     * mean anything. It is enforced by withholding consent rather than by hiding the UI: the
     * service pairs a viewer with a target only if the target's uploaded set contains the viewer,
     * so an empty set is a refusal the other client cannot talk its way past.
     */
    public static Set<UUID> allowedViewers() {
        SpectateConfig config = SpectateConfig.get();
        Set<UUID> out = new HashSet<>();
        if (!config.allowSpectators) {
            return out;
        }
        for (Entry entry : config.trusted) {
            try {
                out.add(entry.id());
            } catch (IllegalArgumentException | NullPointerException ignored) {
                // A malformed id is skipped rather than failing the upload: one bad hand-edit in
                // the config should not revoke everyone else's access.
            }
        }
        return out;
    }

    /**
     * The menu's candidate list: who this client might be able to watch.
     *
     * <p>Independent of {@link SpectateConfig#allowSpectators}, which governs being watched. A
     * player who has turned spectators off can still spectate others, and conflating the two would
     * make the switch do something it does not say.
     */
    public static List<Entry> candidates() {
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Entry entry : SpectateConfig.get().trusted) {
            if (entry == null || entry.uuid == null) {
                continue;
            }
            try {
                entry.id();
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (seen.add(entry.uuid)) {
                out.add(entry);
            }
        }
        return out;
    }

    /** True if this player is already on the list. */
    public static boolean isTrusted(UUID id) {
        return id != null && allowedViewers().contains(id);
    }

    /** Adds a player, or refreshes the cached name of one already present. Saves. */
    public static void add(UUID id, String name) {
        if (id == null) {
            return;
        }
        SpectateConfig config = SpectateConfig.get();
        String key = id.toString();
        for (Entry entry : config.trusted) {
            if (key.equals(entry.uuid)) {
                if (name != null && !name.isBlank()) {
                    entry.name = name;
                }
                config.save();
                return;
            }
        }
        config.trusted.add(new Entry(id, name));
        config.save();
    }

    /** Removes a player. Saves. Returns true if they were on the list. */
    public static boolean remove(UUID id) {
        if (id == null) {
            return false;
        }
        SpectateConfig config = SpectateConfig.get();
        String key = id.toString();
        boolean removed = config.trusted.removeIf(e -> key.equals(e.uuid));
        if (removed) {
            config.save();
        }
        return removed;
    }
}
