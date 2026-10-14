package net.cr512.modernopt.mixin;

import com.mojang.blaze3d.platform.Window;
import net.cr512.modernopt.render.RenderScalePhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mirrors RenderScale's phase-scoped window sizing strategy: world rendering
 * sees the active render-target dimensions, while GUI rendering sees native size.
 */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = "getWidth", at = @At("RETURN"), cancellable = true)
    private void modernopt$scaledWidth(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(RenderScalePhase.scaleWidth(cir.getReturnValueI()));
    }

    @Inject(method = "getHeight", at = @At("RETURN"), cancellable = true)
    private void modernopt$scaledHeight(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(RenderScalePhase.scaleHeight(cir.getReturnValueI()));
    }

    @Inject(method = "getGuiScale", at = @At("RETURN"), cancellable = true)
    private void modernopt$scaledGuiScale(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(RenderScalePhase.scaleGui(cir.getReturnValueD()));
    }
}
