package net.cr512.modernopt.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import net.cr512.modernopt.render.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the OpenGL viewport matched to the render scale target.
 *
 * <p>While the world is being rendered into the scaled target, vanilla code occasionally sets a
 * viewport sized for the window (for example by clearing a full resolution helper target) and
 * then rebinds the world target with {@code bindWrite(false)}, which intentionally does not touch
 * the viewport. Everything drawn afterwards - entities, the hand, particles - would then be
 * rasterised as if the framebuffer were twice as large and be clipped away outside a corner.
 * Calls that target any other framebuffer are left completely alone.</p>
 */
@Mixin(GlStateManager.class)
public abstract class GlStateManagerMixin {

    @Inject(method = "_viewport", at = @At("HEAD"), cancellable = true)
    private static void modernopt$clampWorldViewport(int x, int y, int width, int height, CallbackInfo callbackInfo) {
        if (RenderPipeline.clampViewport(x, y, width, height)) {
            callbackInfo.cancel();
            return;
        }
        // Remember what OpenGL is really being asked for, whichever framebuffer is bound right
        // now. The scaled target uses this to detect a window sized viewport leaking onto it
        // through a bindWrite(false) that intentionally does not touch the viewport.
        net.cr512.modernopt.render.ScaledRenderTarget.noteViewport(width, height);
    }

    /**
     * Repairs the viewport when the scaled target becomes the draw buffer after a window sized
     * pass (vanilla's item entity / weather helper targets, Iris composites) left a full
     * resolution viewport behind. Those passes bind with {@code setViewport = false}, so the only
     * reliable place to catch every route is the framebuffer binding itself.
     */
    @Inject(method = "_glBindFramebuffer", at = @At("TAIL"))
    private static void modernopt$repairViewportOnBind(int target, int framebuffer, CallbackInfo callbackInfo) {
        RenderPipeline.onFramebufferBound(target, framebuffer);
    }
}
