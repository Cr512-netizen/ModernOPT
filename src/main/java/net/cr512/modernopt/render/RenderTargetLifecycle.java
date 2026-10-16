package net.cr512.modernopt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.cr512.modernopt.mixin.LevelRendererAccessor;
import net.cr512.modernopt.mixin.PostChainAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.StringJoiner;

/**
 * Keeps vanilla's screen-space world targets aligned with the active world
 * resolution. This is intentionally scoped to world-render targets; the main
 * framebuffer and HUD framebuffer remain at native window resolution.
 */
public final class RenderTargetLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger("ModernOPT");

    private RenderTargetLifecycle() {}

    public static boolean syncWorldTargets(Minecraft minecraft, int width, int height) {
        if (minecraft == null || minecraft.levelRenderer == null || width <= 0 || height <= 0) {
            return false;
        }

        try {
            resizeIfNeeded(minecraft.levelRenderer.entityTarget(), width, height);

            // Use PostChain.resize rather than resizing its targets individually:
            // vanilla recalculates the chain's orthographic matrix in this method.
            PostChain entityEffect = ((LevelRendererAccessor) (Object) minecraft.levelRenderer).modernopt$getEntityEffect();
            if (entityEffect != null && ((PostChainAccessor) (Object) entityEffect).modernopt$getFullSizedTargets() != null) {
                boolean resizeChain = ((PostChainAccessor) (Object) entityEffect).modernopt$getFullSizedTargets().stream().anyMatch(target ->
                    target != null && (target.width != width || target.height != height)
                );
                if (resizeChain) {
                    entityEffect.resize(width, height);
                }
            }
            return true;
        } catch (RuntimeException | LinkageError exception) {
            LOGGER.warn("Could not synchronize world auxiliary targets to {}x{}; ModernOPT will bypass scaled rendering for this frame.", width, height, exception);
            return false;
        }
    }

    /** Reports the actual vanilla/Iris-adjacent world targets known to this integration. */
    public static String diagnosticSummary(Minecraft minecraft) {
        if (minecraft == null || minecraft.levelRenderer == null) return "levelRenderer=unavailable";
        try {
            RenderTarget entity = minecraft.levelRenderer.entityTarget();
            String entitySize = describe(entity);
            PostChain entityEffect = ((LevelRendererAccessor) (Object) minecraft.levelRenderer).modernopt$getEntityEffect();
            StringJoiner targets = new StringJoiner(",", "[", "]");
            if (entityEffect != null) {
                var fullSizedTargets = ((PostChainAccessor) (Object) entityEffect).modernopt$getFullSizedTargets();
                if (fullSizedTargets != null) {
                    for (RenderTarget target : fullSizedTargets) targets.add(describe(target));
                }
            }
            return "entityTarget=" + entitySize + ", entityPostChain=" + (entityEffect == null ? "none" : targets);
        } catch (RuntimeException | LinkageError exception) {
            return "targetInspectionFailed=" + exception.getClass().getSimpleName();
        }
    }

    private static String describe(RenderTarget target) {
        return target == null ? "null" : target.width + "x" + target.height;
    }

    private static void resizeIfNeeded(RenderTarget target, int width, int height) {
        if (target != null && (target.width != width || target.height != height)) {
            target.resize(width, height, Minecraft.ON_OSX);
        }
    }
}
