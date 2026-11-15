package net.cr512.modernopt.mixin.iris;

import net.cr512.modernopt.compat.ShadowMapResolution;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Forces Iris' shadow targets to the resolution chosen in the ModernOPT config.
 *
 * <p>{@code ShadowRenderTargets} is the class that allocates the shadow depth texture, the colour
 * buffers and the framebuffers, and its {@code resolution} field is what {@code ShadowRenderer}
 * uses as the shadow viewport, so overriding the constructor argument is enough to resize the
 * whole pass — no framebuffer is touched afterwards.</p>
 *
 * <p>Resolved by name and {@link Pseudo} because Iris is an optional dependency: the mixin config
 * plugin skips this class entirely when Iris is absent.</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderTargets", remap = false)
public abstract class IrisShadowRenderTargetsMixin {

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0,
            require = 0, remap = false)
    private static int modernopt$shadowMapResolution(int resolution) {
        return ShadowMapResolution.apply(resolution);
    }
}
