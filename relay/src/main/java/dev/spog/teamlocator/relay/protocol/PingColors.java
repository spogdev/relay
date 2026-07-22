package dev.spog.teamlocator.relay.protocol;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The five ping colours available to a player, derived from their UUID.
 *
 * <p>Each colour is six hex characters taken from the dashless UUID — chars 0-5, 6-11, 12-17,
 * 18-23, 24-29 — prefixed with {@code #}. The derivation is pure and deterministic, so the client
 * and the relay compute the same five values independently and the relay never has to be told what
 * a player's palette is.
 *
 * <p>That matters for validation: a client sends the colour it wants, and the relay checks it is
 * one of these five before passing it on (see {@code RelayRouter}). A patched client cannot invent
 * a colour, because the only accepted values are the ones its own UUID produces — the exception
 * being an administrator's explicit override, which the relay applies in place of the client's
 * choice entirely.
 *
 * <p>Lives in the relay module so both sides share one implementation; the mod depends on this
 * module already.
 */
public final class PingColors {
    /** How many colours a player may choose between. */
    public static final int COUNT = 5;

    private static final int CHARS_PER_COLOUR = 6;

    private PingColors() {
    }

    /**
     * The player's five colours, in index order, each as {@code #rrggbb} lowercase.
     */
    public static List<String> forPlayer(UUID player) {
        String hex = player.toString().replace("-", "").toLowerCase(Locale.ROOT);
        String[] out = new String[COUNT];
        for (int i = 0; i < COUNT; i++) {
            int from = i * CHARS_PER_COLOUR;
            out[i] = "#" + hex.substring(from, from + CHARS_PER_COLOUR);
        }
        return List.of(out);
    }

    /**
     * The colour at {@code index}, or the first colour when the index is out of range — a client
     * with a corrupt config still gets a valid colour rather than none.
     */
    public static String forPlayer(UUID player, int index) {
        List<String> colours = forPlayer(player);
        return colours.get(index < 0 || index >= colours.size() ? 0 : index);
    }

    /**
     * True if {@code colour} is one of this player's five. Case-insensitive; a null or malformed
     * value is simply not a match.
     */
    public static boolean isValidFor(UUID player, String colour) {
        if (colour == null) {
            return false;
        }
        String normalized = colour.trim().toLowerCase(Locale.ROOT);
        return forPlayer(player).contains(normalized);
    }

    /**
     * True if {@code colour} is a well-formed {@code #rrggbb} value. Used for administrator
     * overrides, which are deliberately not restricted to a player's own palette.
     */
    public static boolean isWellFormed(String colour) {
        if (colour == null || colour.length() != 7 || colour.charAt(0) != '#') {
            return false;
        }
        for (int i = 1; i < colour.length(); i++) {
            char c = Character.toLowerCase(colour.charAt(i));
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /** Normalize a well-formed colour to lowercase {@code #rrggbb}, or null if malformed. */
    public static String normalize(String colour) {
        return isWellFormed(colour) ? colour.trim().toLowerCase(Locale.ROOT) : null;
    }
}
