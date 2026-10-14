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

/**
 * Whether the whitelist is enforced, persisted to {@code whitelist-state.json}.
 *
 * <p>Kept in its own file rather than as a field inside {@code whitelist.json} so that the list
 * and the switch are independent: turning enforcement off must not touch who is on the list, and
 * turning it back on must restore exactly the same set. Storing the flag alongside the entries
 * would mean rewriting the list file to flip a boolean, which is a needless chance to corrupt it.
 *
 * <p><b>Default is on.</b> A fresh deployment with no state file enforces the whitelist, because
 * this mod is private and the safe default for an access gate is closed. An operator who wants it
 * open has to say so, and that choice then persists across restarts like everything else.
 *
 * <p>The write is atomic for the same reason as the list's: a truncated state file that failed to
 * parse would fall back to the default, and silently re-enabling enforcement would lock out
 * everyone an operator had deliberately let in.
 */
public final class WhitelistState {
    private static final Logger LOG = LoggerFactory.getLogger(WhitelistState.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** On-disk shape. An object rather than a bare boolean so fields can be added later. */
    static final class Data {
        boolean enabled = true;

        Data() {
        }

        Data(boolean enabled) {
            this.enabled = enabled;
        }
    }

    private final Path file;
    private volatile boolean enabled;

    private WhitelistState(Path file, boolean enabled) {
        this.file = file;
        this.enabled = enabled;
    }

    /**
     * Load the flag. A missing or unreadable file means enabled, which is the closed default --
     * an access gate that fails open because of a parse error is a worse outcome than one that
     * fails shut, since admins bypass the check and can turn it off again over the network.
     */
    public static WhitelistState load(Path file) {
        if (!Files.exists(file)) {
            return new WhitelistState(file, true);
        }
        try {
            Data data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            boolean on = data == null || data.enabled;
            LOG.info("whitelist enforcement is {}", on ? "on" : "off");
            return new WhitelistState(file, on);
        } catch (IOException | JsonSyntaxException e) {
            LOG.warn("could not read {} ({}); leaving whitelist enforcement on", file, e.toString());
            return new WhitelistState(file, true);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Turn enforcement on or off, persisting immediately.
     *
     * @return false if it was already in that state, so the caller can say so rather than
     *     reporting a no-op as a change
     */
    public synchronized boolean set(boolean on) {
        if (enabled == on) {
            return false;
        }
        enabled = on;
        save();
        LOG.info("whitelist enforcement switched {}", on ? "on" : "off");
        return true;
    }

    private void save() {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(new Data(enabled)) + "\n", StandardCharsets.UTF_8);
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
