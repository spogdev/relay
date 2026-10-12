package dev.spog.spegtate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Who is online and currently accepting viewers, as last reported by the service.
 *
 * <p>This replaced a per-client trust list. That list was both the permission model and the menu's
 * source of names, and removing the first left the second with nothing: a client has no way of its
 * own to know who else is running the mod. The service does -- every authenticated session is in
 * its map -- so it sends the list and this holds it.
 *
 * <p>Only consenting clients appear. Someone who has turned spectating off is absent rather than
 * listed and refused on click, so the menu does not offer something that cannot work.
 *
 * <p>This is display state, not permission. Nothing here is consulted when deciding whether a
 * session may start -- that is the target's own consent, checked at the service against the UUID
 * it verified. A stale or forged roster can at worst put a row in a menu whose click is then
 * refused.
 */
public final class SpectateRoster {
    /** One online player who is accepting viewers. */
    public record Peer(UUID id, String name, String version) {
        /** The name to show, falling back to a short id when the service sent none. */
        public String display() {
            if (name != null && !name.isBlank()) {
                return name;
            }
            return id == null ? "?" : id.toString().substring(0, 8);
        }
    }

    // Written by the socket's reader thread, read by the render thread.
    private static volatile List<Peer> peers = List.of();

    private SpectateRoster() {
    }

    /** Replaces the roster with what the service just sent. */
    public static void set(List<Peer> next) {
        peers = List.copyOf(next);
    }

    /** The current roster. Never null; empty before the first report. */
    public static List<Peer> peers() {
        return peers;
    }

    /**
     * Forgets the roster.
     *
     * <p>Called when the connection drops, because a disconnected client cannot know who is still
     * online and showing the last known list would offer rows that are no longer true.
     */
    public static void clear() {
        peers = List.of();
    }

    /** Parses the service's roster message. Malformed entries are skipped, not fatal. */
    public static List<Peer> parse(com.google.gson.JsonArray array) {
        List<Peer> out = new ArrayList<>();
        for (var element : array) {
            try {
                var obj = element.getAsJsonObject();
                UUID id = UUID.fromString(obj.get("uuid").getAsString());
                String name = obj.has("name") ? obj.get("name").getAsString() : "";
                String version = obj.has("version") ? obj.get("version").getAsString() : "";
                out.add(new Peer(id, name, version));
            } catch (RuntimeException ignored) {
                // One bad entry should not cost the whole roster.
            }
        }
        return out;
    }
}
