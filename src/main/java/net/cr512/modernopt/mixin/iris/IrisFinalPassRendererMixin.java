package net.cr512.modernopt.mixin.iris;

import net.cr512.modernopt.render.GpuProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times Iris' final pass, the last full-screen pass before the frame is presented. */
@Mixin(targets = "net.irisshaders.iris.pipeline.FinalPassRenderer", remap = false)
public abstract class IrisFinalPassRendererMixin {

    @Inject(method = "renderFinalPass", at = @At("HEAD"), require = 0, remap = false)
    private void modernopt$beginFinalPass(CallbackInfo callbackInfo) {
        GpuProfiler.begin(GpuProfiler.FINAL);
    }

    @Inject(method = "renderFinalPass", at = @At("RETURN"), require = 0, remap = false)
    private void modernopt$endFinalPass(CallbackInfo callbackInfo) {
        GpuProfiler.end(GpuProfiler.FINAL);
    }
}
