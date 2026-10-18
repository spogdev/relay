package dev.spog.spegtate.mixin;

import dev.spog.spegtate.SpectateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Lets a viewer load skins that carry no usable signature.
 *
 * <p>This is the whole of the skin problem, and the reason it kept coming back in new places.
 * {@code PlayerInfo.createSkinLookup} computes
 * {@code requireSecure = !minecraft.isLocalPlayer(profile.id())} -- true for <em>everyone except
 * yourself</em> -- and {@code SkinManager.createLookup} then filters the fetched
 * textures on {@code secure()}, which is only true when the property arrived SIGNED. So on a
 * viewer, whose own account is the one profile that is never in the stream, every player is
 * held to a signature requirement and anyone whose property is not signed renders as the
 * default skin.
 *
 * <p>Previous attempts worked at the other end, forwarding the target's signature when it
 * looked genuine. That can only ever be as good as what the target's own server supplied, and
 * it frequently supplies nothing usable: proxies strip signatures, servers rewrite profiles for
 * nicked players, and Mojang itself returns an unsigned property for some accounts. Each of
 * those is a separate way for the same symptom to reappear, which is what kept happening --
 * most recently for the target, who is never "self" on a viewer and so is held to the same
 * requirement as everybody else.
 *
 * <p>Dropping the requirement removes the entire class of failure rather than another instance
 * of it. The textures are still fetched by the client's own skin provider from Mojang's session
 * server, as they always were; the only change is that an unsigned answer is now accepted.
 *
 * <p><b>Scoped to a live spectate session.</b> Outside one this returns the argument untouched,
 * so ordinary multiplayer keeps vanilla's verification exactly as it was. What the relaxation
 * can do at worst is show a skin the viewer's client did not cryptographically verify, on a
 * client that is not a participant in the game it is watching.
 */
@Mixin(net.minecraft.client.resources.SkinManager.class)
public abstract class SpectateSkinSecurityMixin {
    @ModifyVariable(
            method = "createLookup(Lcom/mojang/authlib/GameProfile;Z)Ljava/util/function/Supplier;",
            at = @At("HEAD"),
            argsOnly = true)
    private boolean spegtate$allowUnsigned(boolean requireSecure) {
        return requireSecure && !SpectateManager.get().isViewing();
    }
}
