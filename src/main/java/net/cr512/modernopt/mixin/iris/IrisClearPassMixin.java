// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.mixin.iris;

import net.cr512.modernopt.compat.ShadowClearRegistry;
import net.cr512.modernopt.config.ModernOptSettings;
import net.cr512.modernopt.render.RenderPipeline;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the held shadow map from being cleared.
 *
 * <p>The pipeline clears its shadow targets once per frame before the shadow pass, through clear
 * passes that are tagged when the pipeline creates them (see
 * {@link IrisClearPassCreatorMixin}). On frames that reuse the map those passes are skipped;
 * all other clear passes (main colour targets, colortex, etc.) are untouched.</p>
 */
@Mixin(targets = "net.irisshaders.iris.targets.ClearPass", remap = false)
public abstract class IrisClearPassMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void modernopt$holdShadowMap(Vector4f color, CallbackInfo callbackInfo) {
        int interval = Math.max(1, ModernOptSettings.shadowUpdateInterval());
        if (RenderPipeline.shadowHoldShouldDraw(interval)) {
            return;
        }
        if (ShadowClearRegistry.isShadowPass(this)) {
            callbackInfo.cancel();
            return;
        }
        if (ShadowClearRegistry.clearsShadowFramebuffer(this, RenderPipeline.shadowHoldFramebuffer())) {
            callbackInfo.cancel();
            return;
        }
        if (ShadowClearRegistry.clearsShadowViewport(this,
                net.cr512.modernopt.compat.ShadowMapResolution.applied())) {
            callbackInfo.cancel();
        }
    }
}
