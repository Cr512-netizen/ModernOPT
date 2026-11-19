// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import net.cr512.modernopt.config.ModernOptSettings;
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
        net.cr512.modernopt.render.WorldCpuProfiler.noteViewport();
        if (RenderPipeline.clampViewport(x, y, width, height)) {
            net.cr512.modernopt.render.WorldCpuProfiler.noteClampRepair();
            callbackInfo.cancel();
            return;
        }
        // Remember what OpenGL is really being asked for, whichever framebuffer is bound right
        // now. The scaled target uses this to detect a window sized viewport leaking onto it
        // through a bindWrite(false) that intentionally does not touch the viewport.
        net.cr512.modernopt.render.ScaledRenderTarget.noteViewport(width, height);
    }

    /**
     * Keeps the held shadow depth map from being cleared.
     *
     * <p>Iris binds the shadow depth-source framebuffer and clears its depth buffer directly at
     * the start of every level frame, before the shadow renderer runs. Skipping the clear passes
     * alone therefore still wiped the depth map on every reused frame. This checks the bound
     * draw framebuffer and suppresses the depth clear only for the shadow map; every other clear
     * (main target, GUI, helper buffers) is untouched.</p>
     */
    @Inject(method = "_clear", at = @At("HEAD"), cancellable = true)
    private static void modernopt$holdShadowDepthClear(int mask, boolean onMac, CallbackInfo callbackInfo) {
        if ((mask & org.lwjgl.opengl.GL11C.GL_DEPTH_BUFFER_BIT) == 0) {
            return;
        }
        int interval = Math.max(1, ModernOptSettings.shadowUpdateInterval());
        if (RenderPipeline.shadowHoldShouldDraw(interval)) {
            return;
        }
        int shadowFramebuffer = RenderPipeline.shadowHoldFramebuffer();
        if (shadowFramebuffer <= 0) {
            return;
        }
        int bound = org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (bound == shadowFramebuffer) {
            callbackInfo.cancel();
        }
    }

    /**
     * Repairs the viewport when the scaled target becomes the draw buffer after a window sized
     * pass (vanilla's item entity / weather helper targets, Iris composites) left a full
     * resolution viewport behind. Those passes bind with {@code setViewport = false}, so the only
     * reliable place to catch every route is the framebuffer binding itself.
     */
    @Inject(method = "_glBindFramebuffer", at = @At("TAIL"))
    private static void modernopt$repairViewportOnBind(int target, int framebuffer, CallbackInfo callbackInfo) {
        net.cr512.modernopt.render.WorldCpuProfiler.noteFramebufferBind();
        RenderPipeline.onFramebufferBound(target, framebuffer);
    }
}
