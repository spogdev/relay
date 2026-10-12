package dev.spog.spegtate.mixin;

import dev.spog.spegtate.host.LoopbackHost;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes a spectate loopback report as a remote connection rather than a memory one.
 *
 * <p>This is the fix for the viewer failing registry loading with dozens of
 * "Missing tag: 'client:enchantable/...' in 'client:item'" errors, and the reason is narrow
 * enough to be worth writing down.
 *
 * <p>{@code RegistryDataCollector.loadNewElementsAndTags} splits the tags it received in two.
 * Tags for a <em>networkable</em> registry -- one whose contents arrive as
 * {@code DynamicRegistriesS2CPacket} -- are merged into those contents. Tags for everything
 * else, including {@code client:item}, which is built into the client and never synced, take the
 * other branch:
 *
 * <pre>
 *   if (!isNetworkable(key)) {
 *       if (isMemoryConnection) return;   // &lt;-- skipped entirely
 *       pending.add(resolveRegistryTags(...));
 *   }
 * </pre>
 *
 * <p>The skip is correct for singleplayer: the integrated server shares the client's process, so
 * its built-in registries already hold those tags and re-resolving them is pointless work. It is
 * wrong for a spectate loopback, which is a {@code LocalChannel} -- and therefore a "memory
 * connection" by {@link ClientConnection#isMemoryConnection()}'s {@code instanceof} test -- but is
 * replaying a <em>remote</em> server's data with no integrated server behind it at all. The item
 * tags were being sent, received, and then deliberately discarded.
 *
 * <p>Scoped to loopbacks this mod created. Singleplayer and LAN connections are also local
 * channels and must keep their existing behaviour.
 */
@Mixin(ClientConnection.class)
public abstract class LoopbackNotMemoryMixin {
    @Inject(method = "isLocal", at = @At("HEAD"), cancellable = true)
    private void spegtate$spectateIsRemote(CallbackInfoReturnable<Boolean> cir) {
        if (LoopbackHost.isLoopback((ClientConnection) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
