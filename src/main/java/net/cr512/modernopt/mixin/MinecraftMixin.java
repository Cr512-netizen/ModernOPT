package net.cr512.modernopt.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;

import net.cr512.modernopt.render.RenderPipeline;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes {@code Minecraft#getMainRenderTarget()} answer with the scaled target while the world is
 * being rendered. This single redirect is what lets vanilla and modded world rendering (Sodium
 * chunk pass, entity/sky/weather layers, Distant Horizons, post effects) keep working unchanged:
 * they all ask the game for "the current target" instead of caching it.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "getMainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;", at = @At("RETURN"), cancellable = true)
    private void modernopt$scaledWorldTarget(CallbackInfoReturnable<RenderTarget> callbackInfo) {
        // Iris and Sodium ask for "the main target" while rendering the shadow map too (Sodium's
        // shader reset rebinds it after every chunk layer). Answering with the scaled colour target
        // there would pull shadow-pass geometry back into the visible image, so keep the real main
        // target for the duration of the shadow pass.
        if (RenderPipeline.shadowPassActive()) {
            return;
        }
        RenderTarget override = RenderPipeline.currentWorldTarget();
        if (override != null) {
            callbackInfo.setReturnValue(override);
        }
    }
}
