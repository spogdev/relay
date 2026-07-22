package dev.spog.teamlocator.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Decides whether a typed message belongs to the relay rather than the Minecraft server.
 *
 * <p>Kept out of the mixin so the decision is ordinary testable code rather than bytecode-injected
 * logic, and so the mixin stays a two-line adapter.
 */
@Environment(EnvType.CLIENT)
public final class RelayChatRouter {
    private RelayChatRouter() {
    }

    /**
     * Route a message typed into chat.
     *
     * @return true if the relay consumed it, meaning the caller must NOT send it to the server
     */
    public static boolean handleOutgoing(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        var config = TeamLocatorClient.CONFIG;
        if (!config.chatEnabled) {
            return false; // feature off: the prefix means nothing, let it go to the server as typed
        }
        String prefix = config.chatPrefix;
        if (prefix == null || prefix.length() != 1 || !message.startsWith(prefix)) {
            return false;
        }

        String text = message.substring(prefix.length()).trim();
        if (text.isEmpty()) {
            // The prefix alone. Consume it anyway rather than passing a bare "#" to public chat,
            // which is exactly the leak this feature exists to prevent.
            return true;
        }
        if (!TeamLocatorClient.RELAY.isReady()) {
            // Consume it and say so. Falling through to the server would post a message meant for
            // your group into public chat -- the one outcome worth failing loudly to avoid.
            RelayChat.send(Component.literal("Not connected to the relay — message not sent.")
                    .withStyle(ChatFormatting.RED));
            return true;
        }
        TeamLocatorClient.RELAY.sendChat(text);
        return true;
    }
}
