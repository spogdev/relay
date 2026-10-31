package dev.spog.teamlocator.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.spog.teamlocator.TeamLocatorConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Language;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Relay's own translation table, deliberately kept out of the game's.
 *
 * <p>A server can fingerprint a client by sending {@code {"translate":"some.mod.key","fallback":"x"}}
 * and observing whether the key resolved. Anything in vanilla's translation table is probeable that
 * way, so a mod that ships {@code assets/<id>/lang/en_us.json} announces itself to every server it
 * joins, whether or not the mod ever sends a packet.
 *
 * <p>So relay's strings live here instead. They are read from {@code assets/relay/strings/}, which
 * is not a path the vanilla language loader scans, and resolved to {@link Text#literal} before
 * they reach the game. Nothing relay-specific is ever added to {@link Language}, so the probe finds
 * the same empty result it finds on a vanilla client.
 *
 * <p>This is not obfuscation of behaviour: a server can still see what relay <em>does</em>. It only
 * removes the passive fingerprint, so detection requires observing conduct rather than reading a
 * table the mod had no functional reason to publish.
 *
 * <p>The vanilla language switcher keeps working: {@link #reload} re-reads the table for whichever
 * code is selected, and is called on resource reload like any other language consumer.
 */
public final class RelayLang {
    private static final Gson GSON = new Gson();
    private static final String FALLBACK_CODE = "en_us";

    /** Strings for the selected language, falling back to English per key. */
    private static volatile Map<String, String> active = Map.of();
    private static volatile Map<String, String> fallback = Map.of();
    private static volatile String loadedCode = "";

    private RelayLang() {
    }

    /**
     * Loads the table for the currently selected language.
     *
     * <p>Safe to call repeatedly; it is a no-op when the selected code has not changed.
     */
    public static void reload() {
        reload(false);
    }

    /**
     * Loads the table, optionally re-reading even when the selected language has not changed.
     *
     * @param force true on a resource reload, where the code can stay the same while the files
     *              behind it change, so the cheap code comparison would wrongly skip the read
     */
    public static void reload(boolean force) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String code = mc == null || mc.getLanguageManager() == null
                ? FALLBACK_CODE : mc.getLanguageManager().getLanguage();
        if (code == null || code.isEmpty()) {
            code = FALLBACK_CODE;
        }
        if (!force && code.equals(loadedCode) && !active.isEmpty()) {
            return;
        }

        if (force || fallback.isEmpty()) {
            fallback = read(FALLBACK_CODE);
        }
        active = code.equals(FALLBACK_CODE) ? fallback : read(code);
        loadedCode = code;
    }

    /**
     * Reads one language file from the mod jar.
     *
     * <p>Read off the classpath rather than through the {@code ResourceManager}: these files are not
     * registered resources, so they are not reachable from a resource pack, and a pack therefore
     * cannot inject or override relay's strings.
     */
    private static Map<String, String> read(String code) {
        String path = "/assets/" + TeamLocatorConstants.MOD_ID + "/strings/" + code + ".json";
        try (InputStream in = RelayLang.class.getResourceAsStream(path)) {
            if (in == null) {
                return Map.of();
            }
            JsonObject json = GSON.fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            if (json == null) {
                return Map.of();
            }
            Map<String, String> out = new HashMap<>();
            for (String key : json.keySet()) {
                out.put(key, json.get(key).getAsString());
            }
            return Map.copyOf(out);
        } catch (IOException | RuntimeException e) {
            TeamLocatorConstants.LOGGER.warn("Could not read strings for {}", code, e);
            return Map.of();
        }
    }

    /**
     * Keybind names, which cannot be served from {@link #raw} alone.
     *
     * <p>These are the only relay strings the game's own screens look up by key, so they are the
     * only ones that need to be answerable through {@code ClientLanguage}. They are held separately
     * from the general table to keep that exposed set as small and as explicit as possible.
     */
    private static final Map<String, String> KEYBINDS = Map.of(
            "key.category.relay.main", "Relay",
            "key.relay.toggle_target", "Toggle Trust for Targeted Player",
            "key.relay.open_config", "Open Relay Menu",
            "key.relay.toggle_hud", "Toggle HUD",
            "key.relay.toggle_in_world_icons", "Toggle Player Markers",
            "key.relay.map_ping", "Place Ping",
            "key.relay.show_paths", "Show Paths (Hold)");

    /**
     * Set while a screen that legitimately shows keybind names is on screen.
     *
     * <p>Not thread-local: lookups happen on the render thread, and a probe is a server-sent
     * component rendered in chat, which can land inside this window if the controls screen happens
     * to be open. That is acceptable -- it requires the user to have the controls screen open at
     * that moment, which no server can arrange, and it is the narrowest window that still lets the
     * screen render correctly.
     */
    private static volatile boolean resolvingKeybinds;

    public static void setResolvingKeybinds(boolean resolving) {
        resolvingKeybinds = resolving;
    }

    public static boolean isResolvingKeybinds() {
        return resolvingKeybinds;
    }

    public static boolean hasKeybind(String key) {
        return KEYBINDS.containsKey(key);
    }

    /** A keybind's name, translated when the active language provides one. */
    public static String keybind(String key) {
        String translated = active.get(key);
        if (translated == null) {
            translated = fallback.get(key);
        }
        return translated != null ? translated : KEYBINDS.getOrDefault(key, key);
    }

    /** The raw string for a key, or the key itself when missing, as vanilla does. */
    public static String raw(String key) {
        String value = active.get(key);
        if (value == null) {
            value = fallback.get(key);
        }
        return value == null ? key : value;
    }

    /**
     * A key's text, with {@code %s} placeholders filled.
     *
     * <p>Returns a literal component, never a translatable one, so the string is already resolved by
     * the time the game sees it.
     */
    public static MutableText text(String key, Object... args) {
        return Text.literal(string(key, args));
    }

    /** As {@link #text} but plain, for the places that want a {@code String}. */
    public static String string(String key, Object... args) {
        String raw = raw(key);
        if (args.length == 0) {
            return raw;
        }
        try {
            return String.format(raw, args);
        } catch (RuntimeException e) {
            // A malformed pattern should not take the UI down with it.
            return raw;
        }
    }
}
