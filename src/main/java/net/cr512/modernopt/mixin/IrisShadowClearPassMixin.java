package net.cr512.modernopt.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents Iris' shadow-map clear passes from erasing the cached map. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.targets.ClearPass", remap = false)
public abstract class IrisShadowClearPassMixin {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void modernopt$keepCachedShadowTextures(org.joml.Vector4f clearColor, CallbackInfo ci) {
        if (IrisShadowFrameSkipMixin.modernopt$shouldSkipShadowSetupWork()) {
            ci.cancel();
        }
    }
}
