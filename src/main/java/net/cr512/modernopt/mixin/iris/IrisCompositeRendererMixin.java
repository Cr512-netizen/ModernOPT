package net.cr512.modernopt.mixin.iris;

import net.cr512.modernopt.render.GpuProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times Iris' main composite chain (every {@code composite}/{@code final} pass of the pack). */
@Mixin(targets = "net.irisshaders.iris.pipeline.CompositeRenderer", remap = false)
public abstract class IrisCompositeRendererMixin {

    @Inject(method = "renderAll", at = @At("HEAD"), require = 0, remap = false)
    private void modernopt$beginCompositePasses(CallbackInfo callbackInfo) {
        GpuProfiler.begin(GpuProfiler.COMPOSITE);
    }

    @Inject(method = "renderAll", at = @At("RETURN"), require = 0, remap = false)
    private void modernopt$endCompositePasses(CallbackInfo callbackInfo) {
        GpuProfiler.end(GpuProfiler.COMPOSITE);
    }
}
