// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.Window;
import net.cr512.modernopt.render.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reports the internal render resolution while a mode that scales the whole frame is active.
 *
 * <p>The plain world-only path leaves the window alone: the main target field is swapped instead,
 * so subsystems that size buffers from the window are not forced through a getter hook on every
 * call. Whole-window mode still needs the override because the window blit and GUI layout have to
 * agree with the small main target. {@code @ModifyReturnValue} avoids allocating a callback object
 * on every {@code getWidth}/{@code getHeight} call.</p>
 */
@Mixin(Window.class)
public abstract class WindowMixin {

    @ModifyReturnValue(method = "getWidth()I", at = @At("RETURN"))
    private int modernopt$scaledWidth(int original) {
        int override = RenderPipeline.windowWidthOverride();
        return override > 0 ? override : original;
    }

    @ModifyReturnValue(method = "getHeight()I", at = @At("RETURN"))
    private int modernopt$scaledHeight(int original) {
        int override = RenderPipeline.windowHeightOverride();
        return override > 0 ? override : original;
    }

    /** Presents the whole-window-scaled frame just before the back buffer is swapped. */
    @Inject(method = "updateDisplay()V", at = @At("HEAD"))
    private void modernopt$presentWholeWindow(CallbackInfo callbackInfo) {
        RenderPipeline.endWholeWindowFrame();
    }
}
