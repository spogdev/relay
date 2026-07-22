package dev.spog.teamlocator.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The five ping colours derived from a player's UUID: six hex characters each, taken from chars
 * 0-5, 6-11, 12-17, 18-23 and 24-29 of the dashless UUID, prefixed with {@code #}.
 *
 * <p>A deliberate duplicate of {@code dev.spog.teamlocator.relay.protocol.PingColors}. The mod does
 * not depend on the relay module — that is why {@code RelayClient} builds its JSON by hand rather
 * than importing {@code Messages} — so the derivation exists on both sides. It is pure arithmetic
 * over the UUID with no state, so the two cannot drift as long as the slicing matches; the relay's
 * copy is the authority, since it is what validates an incoming ping.
 *
 * <p>Keep the two in step: changing the slicing here without changing it there would make every
 * colour this client picks fail validation and silently fall back to the player's first colour.
 */
@Environment(EnvType.CLIENT)
public final class PingPalette {
    /** How many colours a player may choose between. */
    public static final int COUNT = 5;

    private static final int CHARS_PER_COLOUR = 6;

    private PingPalette() {
    }

    /** The player's five colours, in index order, each as {@code #rrggbb} lowercase. */
    public static List<String> forPlayer(UUID player) {
        String hex = player.toString().replace("-", "").toLowerCase(Locale.ROOT);
        String[] out = new String[COUNT];
        for (int i = 0; i < COUNT; i++) {
            int from = i * CHARS_PER_COLOUR;
            out[i] = "#" + hex.substring(from, from + CHARS_PER_COLOUR);
        }
        return List.of(out);
    }

    /** The colour at {@code index}, or the first when the index is out of range. */
    public static String forPlayer(UUID player, int index) {
        List<String> colours = forPlayer(player);
        return colours.get(index < 0 || index >= colours.size() ? 0 : index);
    }

    /** {@code #rrggbb} to opaque ARGB, or white if the value is malformed. */
    public static int argb(String hex) {
        if (hex != null && hex.length() == 7 && hex.charAt(0) == '#') {
            try {
                return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 0xFFFFFFFF;
    }
}
