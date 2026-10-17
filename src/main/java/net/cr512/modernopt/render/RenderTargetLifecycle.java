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
    private static boolean resizingLevelRenderer;

    private RenderTargetLifecycle() {}

    public static boolean syncWorldTargets(Minecraft minecraft, int width, int height) {
        if (minecraft == null || minecraft.levelRenderer == null || width <= 0 || height <= 0) {
            return false;
        }

        try {
            RenderTarget entityTarget = minecraft.levelRenderer.entityTarget();
            if (entityTarget != null && (entityTarget.width != width || entityTarget.height != height)) {
                // RenderScale resizes LevelRenderer as a whole on 1.21.1. Resizing
                // only entityTarget leaves other vanilla world targets (notably
                // outline/transparency targets) at native resolution, wasting work
                // and creating mixed-resolution passes. Do this only on a real size
                // transition, never every frame.
                if (!resizingLevelRenderer) {
                    resizingLevelRenderer = true;
                    try {
                        minecraft.levelRenderer.resize(width, height);
                    } finally {
                        resizingLevelRenderer = false;
                    }
                }
                // Defensive fallback for loader/mod variants whose resize method
                // does not resize entityTarget as expected.
                resizeIfNeeded(entityTarget, width, height);
            }

            // Use PostChain.resize rather than resizing its targets individually:
            // vanilla recalculates the chain's orthographic matrix in this method.
            PostChain entityEffect = ((LevelRendererAccessor) (Object) minecraft.levelRenderer).modernopt$getEntityEffect();
            if (entityEffect != null && ((PostChainAccessor) (Object) entityEffect).modernopt$getFullSizedTargets() != null) {
                // This method may be reached from render hooks. Avoid creating a
                // Stream/lambda chain during the hot path; inspect the existing list
                // directly and stop at the first mismatched target.
                boolean resizeChain = false;
                for (RenderTarget target : ((PostChainAccessor) (Object) entityEffect).modernopt$getFullSizedTargets()) {
                    if (target != null && (target.width != width || target.height != height)) {
                        resizeChain = true;
                        break;
                    }
                }
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
