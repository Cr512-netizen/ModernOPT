package net.cr512.modernopt.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents shadow-pack compute passes from mutating cached shadow textures. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.program.ComputeProgram", remap = false)
public abstract class IrisShadowComputeMixin {
    @Inject(method = "dispatch", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void modernopt$skipCachedShadowCompute(int width, int height, CallbackInfo ci) {
        if ((net.cr512.modernopt.render.IrisShadowFrameSkipState.skipShadowPass && net.cr512.modernopt.render.IrisShadowFrameSkipState.insideShadowSetup)) {
            ci.cancel();
        }
    }
}
