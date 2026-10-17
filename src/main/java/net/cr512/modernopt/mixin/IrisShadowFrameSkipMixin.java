package net.cr512.modernopt.mixin;

import net.cr512.modernopt.config.ModernOptConfig;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Safety guard for Iris shadow rendering.
 *
 * The previous implementation cancelled IrisRenderingPipeline.renderShadows()
 * to throttle shadow updates. That method also owns state transitions around
 * shadow rendering and PREPARE; retaining only the shadow texture is not enough
 * unless its matching matrices and all target lifecycle state are retained too.
 *
 * Until ModernOPT has a verified snapshot/restore adapter for the supported Iris
 * version, never cancel the Iris shadow pipeline. This intentionally prioritizes
 * correct shadows over the experimental refresh-rate optimization.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisShadowFrameSkipMixin {
    @Unique private static final Logger MODERNOPT_SHADOW_LOGGER = LoggerFactory.getLogger("ModernOPT");
    @Unique private static boolean modernopt$warningLogged;

    @Inject(method = "renderShadows", at = @At("HEAD"), remap = false, require = 0)
    private void modernopt$keepIrisShadowPipelineIntact(CallbackInfo ci) {
        if (!modernopt$warningLogged
            && ModList.get().isLoaded("iris")
            && ModernOptConfig.ENABLED.get()
            && ModernOptConfig.SHADOW_FRAME_SKIP_ENABLED.get()) {
            modernopt$warningLogged = true;
            MODERNOPT_SHADOW_LOGGER.warn(
                "Iris shadow-frame skipping is temporarily bypassed: safe shadow-map and matrix caching is not implemented yet. "
                    + "Iris will refresh shadows every frame to prevent missing shadows."
            );
        }
        // Deliberately do not cancel renderShadows(). Iris must run both its
        // shadow render and the follow-up state/target preparation every frame.
    }
}
