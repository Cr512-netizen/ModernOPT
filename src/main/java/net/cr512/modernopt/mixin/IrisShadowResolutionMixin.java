package net.cr512.modernopt.mixin;

import net.cr512.modernopt.config.ModernOptConfig;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optional Iris integration. Iris reads PackShadowDirectives.getResolution()
 * when it constructs the shadow targets for a shader-pack pipeline. Scaling this
 * value before target creation lets Iris allocate smaller shadow maps without
 * replacing its framebuffers or changing the main world render target.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.properties.PackShadowDirectives", remap = false)
public abstract class IrisShadowResolutionMixin {
    @Inject(method = "getResolution", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private void modernopt$setIrisShadowResolution(CallbackInfoReturnable<Integer> cir) {
        if (!ModList.get().isLoaded("iris") || !ModernOptConfig.IRIS_SHADOW_SCALE_ENABLED.get()) {
            return;
        }

        int original = cir.getReturnValueI();
        if (original <= 0) {
            return;
        }

        int resolution = ModernOptConfig.IRIS_SHADOW_RESOLUTION.get();
        // Keep dimensions aligned to 16-pixel boundaries for common GPU formats.
        resolution = Math.max(512, Math.min(8192, (resolution / 16) * 16));
        cir.setReturnValue(resolution);
    }
}
