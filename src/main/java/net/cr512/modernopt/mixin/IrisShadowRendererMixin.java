package net.cr512.modernopt.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skip only the Iris shadow draw on cached frames. IrisRenderingPipeline.renderShadows
 * continues afterwards and still executes the normal PREPARE stage.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
public abstract class IrisShadowRendererMixin {
    @Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void modernopt$reuseCachedShadowMap(CallbackInfo ci) {
        if (net.cr512.modernopt.render.IrisShadowFrameSkipState.skipShadowPass) {
            ci.cancel();
        }
    }
}
