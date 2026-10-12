package dev.spog.relayspectate.mixin;

import dev.spog.relayspectate.host.LoopbackHost;
import dev.spog.relayspectate.host.LoopbackRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Publishes the registries a loopback client built, so the spectate host can encode against them.
 *
 * <p>Needed to break a circular dependency. The host must switch its outbound protocol to the game
 * phase before it can send the login packet, and that protocol has to encode against the target
 * server's registries. Those registries are built by the viewer's own client as it finishes
 * configuration -- but the only public way to reach them, {@code Minecraft.getConnection()}, reads
 * {@code mc.player.connection}, and the player does not exist until the login packet has been
 * received. So the host cannot send the packet without the registries, and cannot obtain the
 * registries without sending the packet.
 *
 * <p>Taking them here, where the client's play listener is constructed with them, resolves it. The
 * cookie's {@code receivedRegistries} is the set assembled from the replayed configuration, which
 * is exactly what the host needs.
 *
 * <p>Scoped to loopback connections: a real server's registries are the game's own business and
 * nothing here should touch them. This runs at {@code RETURN} rather than {@code HEAD} because a
 * constructor's {@code HEAD} executes before {@code super()}, where Mixin requires a static handler
 * -- and a static one could not see the instance anyway.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerRegistryMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void relayspectate$publishRegistries(Minecraft minecraft, Connection connection,
                                                 CommonListenerCookie cookie, CallbackInfo ci) {
        if (LoopbackHost.isLoopback(connection)) {
            LoopbackRegistries.publish(connection, cookie.receivedRegistries());
        }
    }
}
