package dev.spog.relayspectate.mixin;

import dev.spog.relayspectate.capture.CaptureHooks;
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
 * <p>Filtered to {@link PacketFlow#CLIENTBOUND} connections via the public {@code getReceiving()}
 * rather than a {@code @Shadow} of the backing field. The same class serves the loopback host's
 * server end (see {@code LoopbackHost}), whose inbound flow is SERVERBOUND -- capturing there would
 * feed a viewer's own input back into its session.
 */
@Mixin(Connection.class)
public abstract class ConnectionCaptureMixin {
    @Inject(method = "channelRead0", at = @At("HEAD"))
    private void relayspectate$capture(ChannelHandlerContext ctx, Packet<?> packet,
                                       CallbackInfo ci) {
        if (((Connection) (Object) this).getReceiving() == PacketFlow.CLIENTBOUND) {
            CaptureHooks.onInboundPacket(packet);
        }
    }
}
