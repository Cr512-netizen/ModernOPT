package net.cr512.modernopt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.cr512.modernopt.config.ModernOptConfig;

/** Selects and executes the final world-image pass without changing framebuffer ownership. */
public final class WorldUpscalePass {
    private WorldUpscalePass() {}

    public static void execute(
        RenderTarget worldTarget,
        RenderTarget originalTarget,
        boolean active,
        boolean scalingActive,
        boolean ssaaActive,
        boolean msaaActive,
        boolean shaderPackInUse
    ) {
        if (originalTarget == null) return;

        if (active && scalingActive && worldTarget != null) {
            boolean upscaled;
            if (ssaaActive) {
                upscaled = Fsr1Renderer.downsampleSsaa(worldTarget, originalTarget);
            } else if (ModernOptConfig.UPSCALER_MODE.get() == 0
                || ((ModernOptConfig.UPSCALER_MODE.get() == 2 || ModernOptConfig.UPSCALER_MODE.get() == 3)
                    && worldTarget.width == originalTarget.width
                    && worldTarget.height == originalTarget.height)) {
                // Bilinear/nearest are identity operations at equal dimensions.
                upscaled = FramebufferBlitter.blitFramebuffer(worldTarget, originalTarget);
            } else {
                upscaled = Fsr1Renderer.render(worldTarget, originalTarget);
            }
            if (!upscaled) FramebufferBlitter.blitOpaque(worldTarget, originalTarget);
        } else if (active && msaaActive && worldTarget != null) {
            // Resolve MSAA directly into the native framebuffer when possible.
            if (!FramebufferBlitter.blitFramebuffer(worldTarget, originalTarget)) {
                FramebufferBlitter.blitOpaque(worldTarget, originalTarget);
            }
        }

        // Iris owns shader-pack AA; never add a second FXAA pass to shader output.
        if (!shaderPackInUse && ModernOptConfig.FXAA_ENABLED.get()) {
            Fsr1Renderer.applyFxaa(originalTarget);
        }
    }
}
