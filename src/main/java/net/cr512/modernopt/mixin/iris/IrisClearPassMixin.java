// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.mixin.iris;

import net.cr512.modernopt.compat.ShadowClearRegistry;
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

    private static final java.util.Set<String> modernopt$loggedViewports = new java.util.HashSet<>();

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void modernopt$holdShadowMap(Vector4f color, CallbackInfo callbackInfo) {
        if (!RenderPipeline.shadowHoldSkipFrame()) {
            return;
        }
        boolean tagged = ShadowClearRegistry.isShadowPass(this);
        boolean byFramebuffer = ShadowClearRegistry.clearsShadowFramebuffer(
                this, RenderPipeline.shadowHoldFramebuffer());
        boolean byViewport = ShadowClearRegistry.clearsShadowViewport(
                this, net.cr512.modernopt.compat.ShadowMapResolution.applied());
        if (tagged || byFramebuffer || byViewport) {
            if (Boolean.getBoolean("modernopt.bench")) {
                String key = tagged + "/" + byFramebuffer + "/" + byViewport;
                if (modernopt$loggedViewports.add(key)) {
                    net.cr512.modernopt.ModernOPT.LOG.info(
                            "[BENCH][SHADOWGL] held clear pass cancelled: tagged={} fbo={} viewport={}",
                            tagged, byFramebuffer, byViewport);
                }
            }
            callbackInfo.cancel();
        } else if (Boolean.getBoolean("modernopt.bench")
                && modernopt$loggedViewports.add("ran")) {
            net.cr512.modernopt.ModernOPT.LOG.info(
                    "[BENCH][SHADOWGL] held clear pass ran: tagged=false fbo=false viewport=false");
        }
    }
}
