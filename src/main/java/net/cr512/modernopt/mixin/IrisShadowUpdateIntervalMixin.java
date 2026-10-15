package net.cr512.modernopt.mixin;

import net.cr512.modernopt.config.ModernOptConfig;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reuses Iris' previous shadow-map contents between configured refresh frames.
 * The first call always renders shadows, and an interval of 1 leaves Iris unchanged.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
public abstract class IrisShadowUpdateIntervalMixin {
    @Unique
    private int modernopt$shadowFrameCounter;

    @Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void modernopt$throttleShadowUpdates(CallbackInfo ci) {
        if (!ModList.get().isLoaded("iris")) {
            return;
        }

        int interval = ModernOptConfig.IRIS_SHADOW_UPDATE_INTERVAL.get();
        if (interval <= 1) {
            modernopt$shadowFrameCounter = 0;
            return;
        }

        if (modernopt$shadowFrameCounter++ % interval != 0) {
            ci.cancel();
        }
    }
}
