package dev.spog.teamlocator.mixin.client;

import dev.spog.teamlocator.client.compat.xaero.WaypointShareOption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;
import xaero.map.mods.gui.Waypoint;

import java.util.ArrayList;

/**
 * Adds "Share to trusted" to the right-click menu of a waypoint on Xaero's World Map.
 *
 * <p>Appends to the list Xaero has already built rather than replacing it, so every stock option
 * (teleport, edit, delete) is untouched and the entry simply appears at the end. {@code @Pseudo}
 * because Xaero is a {@code compileOnly} dependency — without the mod installed this mixin has no
 * target class and is skipped, exactly like the existing tracker mixins.
 */
@Pseudo
@Mixin(targets = "xaero.map.mods.gui.WaypointReader", remap = false)
public abstract class WaypointShareOptionMixin {
    // The descriptor is spelled out because WaypointReader carries two methods of this name: the
    // real one taking a Waypoint, and the compiler-generated bridge taking Object that satisfies
    // the generic superclass. An unqualified match would hit both, and since the bridge delegates
    // to the real method the option would be added twice per menu.
    @Inject(
            method = "getRightClickOptions(Lxaero/map/mods/gui/Waypoint;"
                    + "Lxaero/map/gui/IRightClickableElement;)Ljava/util/ArrayList;",
            at = @At("RETURN"), remap = false, require = 0)
    private void relay$addShareOption(Waypoint waypoint, IRightClickableElement element,
                                      CallbackInfoReturnable<ArrayList<RightClickOption>> cir) {
        ArrayList<RightClickOption> options = cir.getReturnValue();
        if (options == null || waypoint == null) {
            return;
        }
        options.add(new WaypointShareOption(waypoint, element, options.size()));
    }
}
