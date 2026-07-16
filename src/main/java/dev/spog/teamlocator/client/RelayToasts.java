package dev.spog.teamlocator.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;

/**
 * Toasts for the relay connection's health, so a relay that goes down (or comes back) is visible
 * rather than the HUD just quietly emptying out.
 *
 * <p>Both toasts share one {@link SystemToast.Type} and go through
 * {@code SystemToast.addOrUpdate}, which resets a showing toast of that id in place rather than
 * queueing another: a reconnect landing while the "lost" toast is still up swaps it for the
 * "restored" one instead of leaving two contradictory toasts stacked.
 *
 * <p>Fired only on real state changes — never per reconnect attempt — by {@code RelayClient}, which
 * tracks the edge. See {@code RelayClient#notifyDisconnected}.
 */
@Environment(EnvType.CLIENT)
public final class RelayToasts {
    private static final SystemToast.Type CONNECTION_TOAST = new SystemToast.Type();

    private RelayToasts() {
    }

    /** The relay connection dropped; we are retrying in the background. */
    public static void connectionLost() {
        show(Text.translatable("relay.connection.lost"),
                Text.translatable("relay.connection.lost.detail"));
    }

    /** The relay is back and authenticated. */
    public static void connectionRestored() {
        show(Text.translatable("relay.connection.restored"),
                Text.translatable("relay.connection.restored.detail"));
    }

    /**
     * Toasts must be added on the game thread; relay state changes happen on the WebSocket receive
     * thread or the relay executor.
     */
    private static void show(Text title, Text detail) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> SystemToast.show(mc.getToastManager(), CONNECTION_TOAST, title, detail));
    }
}
