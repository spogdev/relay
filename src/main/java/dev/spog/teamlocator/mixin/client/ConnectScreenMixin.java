package dev.spog.teamlocator.mixin.client;

import dev.spog.teamlocator.client.WorldIdentity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.CookieStorage;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes the address of every connection, and whether a transfer asked for it. A transfer keeps the
 * server-list entry you first joined, so this is the only place the address it sent you to shows.
 */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
    @Inject(method = "connect(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;ZLnet/minecraft/client/network/CookieStorage;)V", at = @At("HEAD"))
    private static void relay$noteAddress(Screen parent, MinecraftClient minecraft, ServerAddress address,
                                          ServerInfo data, boolean quickPlay, CookieStorage transfer,
                                          CallbackInfo ci) {
        WorldIdentity.connecting(address, transfer);
    }
}
