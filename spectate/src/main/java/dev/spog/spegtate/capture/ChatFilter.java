package dev.spog.spegtate.capture;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides which of the target's chat a viewer may see.
 *
 * <p>Public chat is forwarded so a viewer can follow what is going on; private messages are not.
 * That split is only partly enforceable, and it is worth being plain about why.
 *
 * <p><b>Vanilla signed chat is decidable.</b> {@code ClientboundPlayerChatPacket} carries a
 * {@code ChatType} naming the message's purpose, so {@code MSG_COMMAND_INCOMING} and the team
 * variants are dropped with certainty.
 *
 * <p><b>Server chat is not.</b> Most servers -- including every one this mod is used on -- send
 * chat as {@code ClientboundSystemChatPacket}: a single pre-formatted component with no type, no sender
 * and no metadata of any kind. Nothing in the packet distinguishes a whisper from a public
 * message; the only signal is how the text happens to read. So the patterns below are a
 * best-effort filter over the common whisper formats, and they are honestly described as such:
 * an unusual format will get through, and a public message that happens to match will be
 * hidden.
 *
 * <p>Given that, the default for untyped chat is to forward it. Dropping everything untyped
 * would hide essentially all chat on the servers this is used on, which defeats the feature;
 * forwarding it with the obvious whisper shapes removed is the useful compromise, chosen
 * deliberately rather than by omission.
 */
public final class ChatFilter {
    private ChatFilter() {
    }

    /**
     * Whisper shapes common across server software, matched case-insensitively against the
     * message's flattened text.
     *
     * <p>Kept deliberately narrow. A loose pattern that caught "from" or "to" anywhere would
     * swallow ordinary conversation, and silently hiding public chat is a worse failure here
     * than missing a whisper -- the viewer can see the target's screen regardless, so this
     * filter is a courtesy, not a security boundary.
     */
    private static final Pattern[] WHISPER = {
            // "X whispers to you: ...", "X whispers: ..."
            Pattern.compile("\\bwhispers?\\b.{0,20}\\byou\\b"),
            Pattern.compile("^\\s*\\[?[\\w\\[\\]§]{1,32}\\s+whispers?\\s*:"),
            // "[X -> You]", "X -> You:", "[You -> X]"
            Pattern.compile("->\\s*(you|me)\\b"),
            Pattern.compile("\\byou\\s*->"),
            // "From X: ...", "To X: ..." -- the Essentials / Spigot default
            Pattern.compile("^\\s*\\[?(from|to)\\]?\\s+[\\w\\[\\]§]{1,32}\\s*:"),
            // "[MSG] X: ...", "(DM) X: ..."
            Pattern.compile("^\\s*[\\[(](msg|dm|pm|whisper|tell)[\\])]"),
            // Vanilla's own untranslated forms, in case a server re-sends them as system text.
            Pattern.compile("\\bwhispers to you\\b"),
    };

    /**
     * Whether this chat packet may be forwarded to a viewer.
     *
     * <p>Only called for packets the allow-list already admits, so anything that is not chat
     * returns true and is handled by the rest of the filter.
     */
    public static boolean isPublic(Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.game
                .ClientboundPlayerChatPacket p) {
            return isPublicType(p.chatType());
        }
        if (packet instanceof net.minecraft.network.protocol.game
                .ClientboundSystemChatPacket p) {
            // An overlay message is the action bar, not chat; it is already forwarded as part of
            // the target's HUD state and is never a private message.
            return p.overlay() || !looksLikeWhisper(p.content());
        }
        if (packet instanceof net.minecraft.network.protocol.game
                .ClientboundDisguisedChatPacket p) {
            return isPublicType(p.chatType());
        }
        return true;
    }

    /**
     * True for a message type that is public.
     *
     * <p>An allow-list of the two public vanilla types rather than a block-list of the private
     * ones: a type added in a future version is then withheld rather than leaked, which is the
     * same default the packet allow-list uses.
     */
    private static boolean isPublicType(ChatType.Bound bound) {
        if (bound == null) {
            return true;
        }
        // A targetName is set only for a message addressed to one player -- which is exactly
        // what a whisper is, and is set by the server rather than inferred from the text. It
        // catches a custom private type that the key check below would not recognise.
        if (bound.targetName().isPresent()) {
            return false;
        }
        var type = bound.chatType();
        if (type == null) {
            return true;
        }
        // Holder.is(key) rather than unwrapKey().orElse(null) == key: an inline holder carries
        // the value without a key, and is() answers false for it rather than throwing, which is
        // the withheld-by-default behaviour this allow-list wants.
        return type.is(ChatType.CHAT)
                || type.is(ChatType.SAY_COMMAND)
                || type.is(ChatType.EMOTE_COMMAND);
    }

    /** Whether a formatted server message reads like a private message. */
    private static boolean looksLikeWhisper(Component content) {
        if (content == null) {
            return false;
        }
        String flat = content.getString().toLowerCase(Locale.ROOT);
        if (flat.isEmpty()) {
            return false;
        }
        for (Pattern p : WHISPER) {
            if (p.matcher(flat).find()) {
                return true;
            }
        }
        return false;
    }
}
