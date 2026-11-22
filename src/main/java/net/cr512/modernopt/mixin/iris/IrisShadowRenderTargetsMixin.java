// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.mixin.iris;

import net.cr512.modernopt.compat.ShadowClearRegistry;
import net.cr512.modernopt.compat.ShadowImageRegistry;
import net.cr512.modernopt.compat.ShadowMapResolution;
import net.cr512.modernopt.config.ModernOptSettings;
import net.cr512.modernopt.render.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forces Iris' shadow targets to the resolution chosen in the ModernOPT config.
 *
 * <p>{@code ShadowRenderTargets} is the class that allocates the shadow depth texture, the colour
 * buffers and the framebuffers, and its {@code resolution} field is what {@code ShadowRenderer}
 * uses as the shadow viewport, so overriding the constructor argument is enough to resize the
 * whole pass — no framebuffer is touched afterwards.</p>
 *
 * <p>Resolved by name and {@link Pseudo} because Iris is an optional dependency: the mixin config
 * plugin skips this class entirely when Iris is absent.</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderTargets", remap = false)
public abstract class IrisShadowRenderTargetsMixin {

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0,
            require = 0, remap = false)
    private static int modernopt$shadowMapResolution(int resolution) {
        ShadowClearRegistry.clear();
        ShadowImageRegistry.clear();
        RenderPipeline.shadowHoldResetTargets();
        return ShadowMapResolution.apply(resolution);
    }

    @Inject(method = "flip(I)V", at = @At("HEAD"), require = 0, remap = false)
    private void modernopt$noteFlip(int index, CallbackInfo callbackInfo) {
        if (Boolean.getBoolean("modernopt.bench")
                && !RenderPipeline.shadowHoldShouldDraw(ModernOptSettings.shadowUpdateInterval())) {
            net.cr512.modernopt.ModernOPT.LOG.info(
                    "[BENCH][SHADOWGL] shadow target {} flipped on a held frame", index);
        }
    }

    @Inject(method = "onFullClear()V", at = @At("HEAD"), require = 0, remap = false)
    private void modernopt$noteFullClear(CallbackInfo callbackInfo) {
        if (Boolean.getBoolean("modernopt.bench")
                && !RenderPipeline.shadowHoldShouldDraw(ModernOptSettings.shadowUpdateInterval())) {
            net.cr512.modernopt.ModernOPT.LOG.info(
                    "[BENCH][SHADOWGL] shadow targets requested a full clear on a held frame");
        }
    }
}
