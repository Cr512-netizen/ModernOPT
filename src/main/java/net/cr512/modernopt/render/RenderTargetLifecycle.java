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

        RenderTarget entityTarget = null;
        PostChain entityEffect = null;
        int oldEntityWidth = -1;
        int oldEntityHeight = -1;
        int oldChainWidth = -1;
        int oldChainHeight = -1;
        boolean entityResizeAttempted = false;
        boolean chainResizeAttempted = false;
        try {
            entityTarget = minecraft.levelRenderer.entityTarget();
            entityEffect = ((LevelRendererAccessor) (Object) minecraft.levelRenderer).modernopt$getEntityEffect();
            oldEntityWidth = entityTarget == null ? -1 : entityTarget.width;
            oldEntityHeight = entityTarget == null ? -1 : entityTarget.height;

            var fullSizedTargets = entityEffect == null ? null
                : ((PostChainAccessor) (Object) entityEffect).modernopt$getFullSizedTargets();
            if (fullSizedTargets != null) {
                for (RenderTarget target : fullSizedTargets) {
                    if (target != null) {
                        oldChainWidth = target.width;
                        oldChainHeight = target.height;
                        break;
                    }
                }
            }

            boolean resizeEntity = entityTarget != null && (oldEntityWidth != width || oldEntityHeight != height);
            boolean resizeChain = fullSizedTargets != null && fullSizedTargets.stream().anyMatch(target ->
                target != null && (target.width != width || target.height != height)
            );

            // Validate every requested dimension before mutating any target.
            if (resizeEntity || resizeChain) {
                int maxTextureSize = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_MAX_TEXTURE_SIZE);
                if (width > maxTextureSize || height > maxTextureSize) {
                    throw new IllegalArgumentException("Requested auxiliary target exceeds GL_MAX_TEXTURE_SIZE: " + width + "x" + height);
                }
            }

            if (resizeEntity) {
                entityResizeAttempted = true;
                entityTarget.resize(width, height, Minecraft.ON_OSX);
            }

            // Use PostChain.resize rather than resizing its targets individually:
            // vanilla recalculates the chain's orthographic matrix in this method.
            if (resizeChain) {
                chainResizeAttempted = true;
                entityEffect.resize(width, height);
            }
            return true;
        } catch (RuntimeException | LinkageError exception) {
            // Resizing a target can fail after it has already destroyed its old
            // attachments. Best-effort rollback prevents the next vanilla frame
            // from inheriting a mixed set of native/scaled auxiliary dimensions.
            if (chainResizeAttempted && entityEffect != null && oldChainWidth > 0 && oldChainHeight > 0) {
                try {
                    entityEffect.resize(oldChainWidth, oldChainHeight);
                } catch (RuntimeException | LinkageError rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
            }
            if (entityResizeAttempted && entityTarget != null && oldEntityWidth > 0 && oldEntityHeight > 0) {
                try {
                    entityTarget.resize(oldEntityWidth, oldEntityHeight, Minecraft.ON_OSX);
                } catch (RuntimeException | LinkageError rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
            }
            LOGGER.warn("Could not synchronize world auxiliary targets to {}x{}; ModernOPT will bypass scaled rendering for this frame. Rollback was attempted.", width, height, exception);
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
}
