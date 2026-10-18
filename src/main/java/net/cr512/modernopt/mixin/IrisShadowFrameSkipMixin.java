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
 * Safety gate for the experimental Iris shadow-cache setting.
 *
 * A whole-method cancellation of ShadowRenderer.renderShadows would also skip
 * its composite pass and GL-state cleanup. Until a version-verified, narrower
 * integration is implemented, keep fresh shadows and make the unsupported
 * setting explicit rather than partially suppressing Iris operations.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisShadowFrameSkipMixin {
    @Unique private static final Logger MODERNOPT_LOGGER = LoggerFactory.getLogger("ModernOPT");
    @Unique private static boolean modernopt$warnedShadowCacheDisabled;

    @Inject(method = "beginLevelRendering", at = @At("HEAD"), remap = false, require = 0)
    private void modernopt$disableUnsafeShadowCache(CallbackInfo ci) {
        net.cr512.modernopt.render.IrisShadowFrameSkipState.skipShadowPass = false;
        net.cr512.modernopt.render.IrisShadowFrameSkipState.insideShadowSetup = false;

        if (ModernOptConfig.ENABLED.get() && ModernOptConfig.SHADOW_FRAME_SKIP_ENABLED.get()
            && ModList.get().isLoaded("iris") && !modernopt$warnedShadowCacheDisabled) {
            modernopt$warnedShadowCacheDisabled = true;
            MODERNOPT_LOGGER.warn("Iris shadow-frame skipping is not safe in this build; fresh shadows will be rendered every frame. The option is reserved until a version-verified hook can preserve Iris' composite pass and GL-state cleanup.");
        }
    }
}
