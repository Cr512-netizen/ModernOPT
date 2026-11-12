package net.cr512.modernopt.mixin;

import com.mojang.blaze3d.platform.Window;
import net.cr512.modernopt.render.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reports the internal render resolution from {@code Window} while the world is being rendered.
 *
 * <p>Redirecting {@code Minecraft#getMainRenderTarget()} alone is not enough: subsystems that size
 * their own buffers from the window (Iris' post-processing chain, vanilla helper targets, weather
 * and entity buffers, Sodium's per-frame allocations) keep allocating at the native resolution and
 * then draw full-resolution passes over a scaled image. The reference RenderScale mod solves this
 * by making {@code Window} answer with the scaled size; this mixin does the same, but only for the
 * duration of the world pass, so the GUI, mouse handling and screenshots stay native.</p>
 */
@Mixin(Window.class)
public abstract class WindowMixin {

    @Inject(method = "getWidth()I", at = @At("HEAD"), cancellable = true)
    private void modernopt$scaledWidth(CallbackInfoReturnable<Integer> callbackInfo) {
        int override = RenderPipeline.windowWidthOverride();
        if (override > 0) {
            callbackInfo.setReturnValue(override);
        }
    }

    @Inject(method = "getHeight()I", at = @At("HEAD"), cancellable = true)
    private void modernopt$scaledHeight(CallbackInfoReturnable<Integer> callbackInfo) {
        int override = RenderPipeline.windowHeightOverride();
        if (override > 0) {
            callbackInfo.setReturnValue(override);
        }
    }
}
