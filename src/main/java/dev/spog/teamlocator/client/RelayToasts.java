package dev.spog.teamlocator.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Toasts for the relay connection's health, so a relay that goes down (or comes back) is visible
 * rather than the HUD just quietly emptying out.
 *
 * <p>Both toasts share one {@link SystemToast.SystemToastId} and go through
 * {@code SystemToast.addOrUpdate}, which resets a showing toast of that id in place rather than
 * queueing another: a reconnect landing while the "lost" toast is still up swaps it for the
 * "restored" one instead of leaving two contradictory toasts stacked.
 *
 * <p>Fired only on real state changes — never per reconnect attempt — by {@code RelayClient}, which
 * tracks the edge. See {@code RelayClient#notifyDisconnected}.
 */
@Environment(EnvType.CLIENT)
public final class RelayToasts {
    private static final SystemToast.SystemToastId CONNECTION_TOAST = new SystemToast.SystemToastId();

    private RelayToasts() {
    }

    /** The relay connection dropped; we are retrying in the background. */
    public static void connectionLost() {
        show(Component.translatable("relay.connection.lost"),
                Component.translatable("relay.connection.lost.detail"));
    }

    /** The relay is back and authenticated. */
    public static void connectionRestored() {
        show(Component.translatable("relay.connection.restored"),
                Component.translatable("relay.connection.restored.detail"));
    }

    /**
     * Toasts must be added on the game thread; relay state changes happen on the WebSocket receive
     * thread or the relay executor.
     */
    private static void show(Component title, Component detail) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> SystemToast.addOrUpdate(mc.getToastManager(), CONNECTION_TOAST, title, detail));
    }
}
