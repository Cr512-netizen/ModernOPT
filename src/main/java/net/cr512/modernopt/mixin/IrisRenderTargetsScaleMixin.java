package net.cr512.modernopt.mixin;

import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.render.RenderScaleController;
import net.cr512.modernopt.render.RenderScalePhase;
import net.cr512.modernopt.render.RenderDiagnostics;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Iris owns its gbuffer color targets independently of Minecraft's main target.
 * Scale the dimensions when Iris initially allocates those buffers, so shader
 * passes (including packs' sky/world passes that write to colortex buffers)
 * can actually shade fewer pixels instead of only scaling Minecraft's final
 * framebuffer.
 *
 * This deliberately targets Iris' non-shadow RenderTargets class; shadow-map
 * resolution remains controlled separately by IrisShadowResolutionMixin.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.targets.RenderTargets", remap = false)
public abstract class IrisRenderTargetsScaleMixin {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private static int modernopt$scaleIrisTargetWidth(int width) {
        return modernopt$scaledDimension(width, true);
    }

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 1, remap = false)
    private static int modernopt$scaleIrisTargetHeight(int height) {
        return modernopt$scaledDimension(height, false);
    }


    /**
     * Iris checks the main framebuffer dimensions every frame. Scaling only the
     * constructor arguments is insufficient: resizeIfNeeded would immediately
     * grow its g-buffers back to native resolution. Scale those dimensions too,
     * so the cached Iris dimensions and all pack-owned color/depth targets stay
     * aligned with ModernOPT's active world resolution.
     */
    @ModifyVariable(method = "resizeIfNeeded", at = @At("HEAD"), argsOnly = true, ordinal = 2, remap = false)
    private int modernopt$scaleIrisResizeWidth(int width) {
        return modernopt$scaledDimension(width, true);
    }

    @ModifyVariable(method = "resizeIfNeeded", at = @At("HEAD"), argsOnly = true, ordinal = 3, remap = false)
    private int modernopt$scaleIrisResizeHeight(int height) {
        return modernopt$scaledDimension(height, false);
    }


    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void modernopt$diagnoseIrisTargets(CallbackInfo ci) {
        RenderDiagnostics.recordIrisTargets(this);
    }

    @Inject(method = "resizeIfNeeded", at = @At("RETURN"), remap = false)
    private void modernopt$diagnoseIrisTargetResize(CallbackInfoReturnable<?> cir) {
        RenderDiagnostics.recordIrisTargets(this);
    }

    private static int modernopt$scaledDimension(int dimension, boolean horizontal) {
        if (dimension <= 0 || !ModernOptConfig.ENABLED.get() || !RenderScaleController.isEnabled()) {
            return dimension;
        }

        // Iris can construct/recreate its pipeline while renderLevel is already
        // using ModernOPT's scaled main target. In that case the constructor
        // arguments are already scaled; scaling them again creates undersized
        // buffers and forces Iris to reallocate them on its next size check.
        if (RenderScalePhase.isActive()) {
            var activeTarget = Minecraft.getInstance().getMainRenderTarget();
            int activeDimension = horizontal ? activeTarget.width : activeTarget.height;
            if (dimension == activeDimension) {
                return dimension;
            }
        }

        // Use the same bounded dimensions as the main ModernOPT world target.
        return horizontal
            ? RenderScaleController.scaledWidth(dimension)
            : RenderScaleController.scaledHeight(dimension);
    }
}
