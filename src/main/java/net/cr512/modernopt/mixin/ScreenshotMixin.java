package net.cr512.modernopt.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.cr512.modernopt.render.RenderPipeline;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Restores the texture unit that {@link Screenshot#takeScreenshot} changes.
 *
 * <p>The vanilla screenshot path binds the main colour texture to whatever texture unit
 * happens to be active and never restores it. That leaves the screen image bound to a unit
 * that the terrain or lightmap sampler uses on the next frame. Sodium and Iris cache texture
 * bindings, so the stale unit may survive the frame and every block face then samples the
 * screen content - visible as the HUD/overlay text smeared over the world after pressing F2.
 * Saving and restoring the exact unit makes screenshots side-effect free.</p>
 */
@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {

    @Inject(
            method = "takeScreenshot(Lcom/mojang/blaze3d/pipeline/RenderTarget;)Lcom/mojang/blaze3d/platform/NativeImage;",
            at = @At("HEAD")
    )
    private static void modernopt$saveTextureState(RenderTarget target, CallbackInfoReturnable<NativeImage> callbackInfo) {
        RenderPipeline.saveTextureStateBeforeScreenshot();
    }

    @Inject(
            method = "takeScreenshot(Lcom/mojang/blaze3d/pipeline/RenderTarget;)Lcom/mojang/blaze3d/platform/NativeImage;",
            at = @At("RETURN")
    )
    private static void modernopt$restoreTextureState(RenderTarget target, CallbackInfoReturnable<NativeImage> callbackInfo) {
        RenderPipeline.restoreTextureStateAfterScreenshot();
    }
}
