package dev.spog.spegtate;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Map;

/**
 * The addon's private string table, and the reason it exists.
 *
 * <p>Relay keeps its strings out of the game's translation table because a server can probe that
 * table to detect mods (MC-265322: plant a sign of {@code {"translate":"key"}} components, read
 * back what the client resolved -- an unchanged key means the mod is absent, anything else means it
 * is present). An addon that registered ordinary lang keys would hand back exactly the fingerprint
 * the base mod went to some trouble to remove, and it would be a worse one: {@code spegtate}
 * keys identify not just Relay but Relay-with-spectating.
 *
 * <p>So the same approach applies here. Strings live in this class, are resolved to literal
 * components, and never enter {@code Language}. Keybinds are the one unavoidable exception -- they
 * must be registered to be bindable, and a registered keybind answers a {@code {"keybind":"key"}}
 * probe through {@code KeyMapping.ALL} regardless of the translation table -- which is what
 * {@code KeybindContentsMixin} handles on the Relay side and
 * {@link dev.spog.spegtate.mixin.SpectateKeybindMixin} handles here.
 *
 * <p>No per-language files, unlike Relay's: this addon is private and English-only, so a single
 * table avoids the resource loading and language-switch plumbing for no loss.
 */
public final class SpectateLang {
    private SpectateLang() {
    }

    /**
     * Keybind names, the only strings the game's own screens look up by key.
     *
     * <p>Held separately from the UI strings for the same reason Relay does it: this is the set
     * that has to be answerable through {@code ClientLanguage}, so it is kept small and explicit.
     */
    private static final Map<String, String> KEYBINDS = Map.of(
            "key.category.spegtate.main", "Spegtate",
            "key.spegtate.open_menu", "Spectate Menu (while spectating)",
            "key.spegtate.stop", "Stop Spectating",
            "key.spegtate.refocus", "Refocus");

    /** UI strings, never looked up by the game. */
    private static final Map<String, String> STRINGS = Map.ofEntries(
            Map.entry("menu.title", "Spectate"),
            Map.entry("menu.empty", "Nobody else is online right now."),
            Map.entry("menu.hint", "Pick someone to watch."),
            Map.entry("menu.stop", "Stop Spectating"),
            Map.entry("menu.close", "Close"),
            Map.entry("menu.allow_on", "Spectating: ON"),
            Map.entry("menu.allow_off", "Spectating: OFF"),
            Map.entry("status.refocused", "Refocused on %s"),
            Map.entry("status.refocused_loading", "Refocused on %s -- loading terrain..."),
            Map.entry("status.refocus_waiting", "Waiting for the target's position."),
            Map.entry("status.connecting", "Connecting to the spectate service..."),
            Map.entry("status.loading", "Loading %s's view..."),
            Map.entry("status.watching", "Spectating %s"),
            Map.entry("status.ended", "The spectate session ended."),
            Map.entry("status.watched", "%s is watching you"),
            Map.entry("status.watched_many", "%d players are watching you"),
            Map.entry("deny.not_trusted", "%s does not have you trusted."),
            Map.entry("deny.no_addon", "%s is not running the spectate addon."),
            Map.entry("deny.offline", "%s is not available to spectate."),
            Map.entry("deny.no_handshake",
                    "%s needs to rejoin their server before they can be spectated."),
            Map.entry("deny.revoked", "%s revoked your access."),
            Map.entry("deny.busy", "You are already spectating someone."),
            Map.entry("deny.version",
                    "%s is on a different Minecraft version -- spectating needs the same one."),
            Map.entry("error.no_service", "The spectate service is not configured."),
            Map.entry("error.failed", "The spectate session could not start."),
            Map.entry("error.in_world", "Leave your world first -- spectating replaces it."),
            Map.entry("error.not_connected", "Not connected to the spectate service."));

    /**
     * Set while a screen that legitimately shows keybind names is building its rows.
     *
     * <p>The addon keeps its own window rather than borrowing Relay's, which is what it used to
     * do. Sharing the flag meant the addon's keybind rows only rendered when Relay was installed
     * to open the window for them -- a hard dependency for the sake of a boolean.
     *
     * <p>Not thread-local: lookups happen on the render thread, and the thing being guarded
     * against is a server-sent component rendered in chat, which can land inside this window if
     * the controls screen happens to be open. That is acceptable -- it needs the user to have the
     * controls screen open at that moment, which no server can arrange -- and it is the narrowest
     * window that still lets the screen render correctly.
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

    /** A keybind's display name. */
    public static String keybind(String key) {
        return KEYBINDS.getOrDefault(key, key);
    }

    /** A UI string as a literal component, so it never touches the translation table. */
    public static MutableComponent text(String key, Object... args) {
        return Component.literal(string(key, args));
    }

    /** A UI string, formatted. */
    public static String string(String key, Object... args) {
        String raw = STRINGS.get(key);
        if (raw == null) {
            return key;
        }
        if (args.length == 0) {
            return raw;
        }
        try {
            return String.format(raw, args);
        } catch (RuntimeException e) {
            // A format mismatch is a bug in the caller, not something to crash a render pass over.
            return raw;
        }
    }
}
