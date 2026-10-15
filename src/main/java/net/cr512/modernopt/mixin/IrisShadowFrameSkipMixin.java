package net.cr512.modernopt.mixin;

import net.cr512.modernopt.config.ModernOptConfig;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional Iris integration that rate-limits shadow-map refreshes while keeping
 * the previous shadow map available for frames in between updates.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
public abstract class IrisShadowFrameSkipMixin {
    private static double modernopt$shadowFrameAccumulator;
    private static boolean modernopt$hasRenderedShadowMap;

    @Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void modernopt$rateLimitShadowUpdates(CallbackInfo ci) {
        if (!ModList.get().isLoaded("iris") || !ModernOptConfig.SHADOW_FRAME_SKIP_ENABLED.get()) {
            modernopt$shadowFrameAccumulator = 0.0;
            modernopt$hasRenderedShadowMap = false;
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        int currentFps = Math.max(1, minecraft.getFps());
        int targetFps = Math.max(40, Math.min(240, ModernOptConfig.SHADOW_UPDATE_FPS.get()));

        // Always render the first map after enabling to avoid sampling an uninitialized texture.
        if (!modernopt$hasRenderedShadowMap) {
            modernopt$hasRenderedShadowMap = true;
            modernopt$shadowFrameAccumulator = 0.0;
            return;
        }

        // Never skip if the game itself is slower than the requested shadow rate.
        if (currentFps <= targetFps) {
            modernopt$shadowFrameAccumulator = 0.0;
            return;
        }

        modernopt$shadowFrameAccumulator += (double) targetFps / currentFps;
        if (modernopt$shadowFrameAccumulator >= 1.0) {
            modernopt$shadowFrameAccumulator -= 1.0;
        } else {
            ci.cancel();
        }
    }
}
