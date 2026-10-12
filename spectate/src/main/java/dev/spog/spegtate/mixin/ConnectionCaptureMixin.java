package dev.spog.spegtate.mixin;

import dev.spog.spegtate.capture.CaptureHooks;
import dev.spog.spegtate.host.LoopbackHost;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Taps the packets this client receives, for the capture side of a spectate session.
 *
 * <p>{@code channelRead0} is the single point every inbound packet passes through, already decoded,
 * for both the configuration and play phases. Tapping here rather than on the individual
 * {@code handleX} methods means one injection instead of forty, and it sees the configuration-phase
 * registry packets that {@code ClientPacketListener} never receives.
 *
 * <p>At {@code HEAD} and never cancelling: the packet is observed on its way to the game and is
 * otherwise untouched. A spectate session must not change what the target's own client does with
 * its traffic.
 *
 * <p>Two connections are excluded. The loopback host's server end reads SERVERBOUND, so the flow
 * check skips it -- capturing there would feed a viewer's own input back into its session. The
 * loopback's <em>client</em> end is the subtle one: it reads CLIENTBOUND, exactly like a real
 * server connection, because that is how {@code connectToLocalServer} builds it. Capturing it
 * would record the replayed handshake as if this client's own server had sent it, overwriting the
 * real recording. {@link LoopbackHost#isLoopback} is what tells them apart.
 */
@Mixin(Connection.class)
public abstract class ConnectionCaptureMixin {
    @Inject(method = "channelRead0", at = @At("HEAD"))
    private void spegtate$capture(ChannelHandlerContext ctx, Packet<?> packet,
                                       CallbackInfo ci) {
        Connection self = (Connection) (Object) this;
        if (self.getReceiving() == PacketFlow.CLIENTBOUND && !LoopbackHost.isLoopback(self)) {
            CaptureHooks.onInboundPacket(packet);
        }
    }
}
