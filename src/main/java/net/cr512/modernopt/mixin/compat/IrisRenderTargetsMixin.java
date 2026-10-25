package net.cr512.modernopt.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Iris's depth attachment in sync with the redirected main render target.
 *
 * <p>Iris does not own a depth buffer for the world: it attaches the depth texture of
 * {@code Minecraft#getMainRenderTarget()} to its own framebuffers. It only refreshes that
 * attachment when the target's depth version counter changes, and a counter cannot express
 * "this is a different target now". While ModernOPT swaps the main target for its scaled one,
 * both targets can report the same version, so Iris keeps drawing against the wrong depth
 * texture (holes in the world, LODs on top of everything).</p>
 *
 * <p>The fix is to treat a changed depth texture <em>identity</em> as a version change. The
 * mixin is {@link Pseudo pseudo}, so the mod still loads when Iris is absent - no compile
 * dependency, and nothing happens without a shader pack.</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.targets.RenderTargets", remap = false)
public abstract class IrisRenderTargetsMixin {

    @Shadow
    private int currentDepthTexture;

    @Shadow
    private int cachedDepthBufferVersion;

    @Inject(method = "resizeIfNeeded", at = @At("HEAD"), remap = false, require = 0)
    private void modernopt$followDepthTexture(int version, int depthTextureId, CallbackInfoReturnable<Boolean> callbackInfo) {
        if (this.currentDepthTexture != depthTextureId) {
            // Any value that differs from the incoming counter forces Iris to re-attach.
            this.cachedDepthBufferVersion = ~version;
        }
    }
}
