package net.cr512.modernopt.mixin.iris;

import net.cr512.modernopt.render.ShadowResolutionPatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rewrites the {@code shadowMapResolution} constant of the shader pack to match the shadow map
 * that ModernOPT actually allocates.
 *
 * <p>Every pack source file passes through {@code JcppProcessor.glslPreprocessSource}, after
 * includes and {@code #if} branches have been resolved, which makes it the single place where the
 * final shader text can be adjusted. Hooking the constructor of {@code ShadowRenderTargets}
 * alone would resize the framebuffer while the shader kept its old texel math.</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor", remap = false)
public abstract class IrisJcppProcessorMixin {

    @Inject(method = "glslPreprocessSource", at = @At("RETURN"), cancellable = true,
            require = 0, remap = false)
    private static void modernopt$rewriteShadowMapResolution(String source, Iterable<?> defines,
            CallbackInfoReturnable<String> callbackInfo) {
        String patched = ShadowResolutionPatcher.rewrite(callbackInfo.getReturnValue());
        if (patched != null) {
            callbackInfo.setReturnValue(patched);
        }
    }
}
