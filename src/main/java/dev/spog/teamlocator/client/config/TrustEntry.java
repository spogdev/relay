package dev.spog.teamlocator.client.config;

import java.util.UUID;

/**
 * One entry in a trust or block list. Identity is the {@link #uuid} (stable and authoritative on
 * online-mode servers); {@link #name} is a display cache refreshed from the tab list and never
 * used for routing. {@link #hidden} lets the user hide their coordinates from this specific player
 * while keeping them on the list.
 */
public class TrustEntry {
    public String uuid;
    public String name;
    public boolean hidden;

    public TrustEntry() {
    }

    public TrustEntry(UUID uuid, String name) {
        this.uuid = uuid.toString();
        this.name = name;
        this.hidden = false;
    }

    public UUID uuid() {
        return UUID.fromString(uuid);
    }
}
