package dev.spog.teamlocator.mixin.client;

import dev.spog.teamlocator.client.WorldIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
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
    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void relay$noteAddress(Screen parent, Minecraft minecraft, ServerAddress address,
                                          ServerData data, boolean quickPlay, TransferState transfer,
                                          CallbackInfo ci) {
        WorldIdentity.connecting(address, transfer);
    }
}
