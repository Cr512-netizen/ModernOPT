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
            RenderTarget entityTarget = minecraft.levelRenderer.entityTarget();
            PostChain entityEffect = ((LevelRendererAccessor) (Object) minecraft.levelRenderer).modernopt$getEntityEffect();
            boolean resizeEntity = entityTarget != null && (entityTarget.width != width || entityTarget.height != height);
            boolean resizeChain = entityEffect != null
                && ((PostChainAccessor) (Object) entityEffect).modernopt$getFullSizedTargets() != null
                && ((PostChainAccessor) (Object) entityEffect).modernopt$getFullSizedTargets().stream().anyMatch(target ->
                    target != null && (target.width != width || target.height != height)
                );

            // Validate every target dimension before mutating any target. This avoids
            // leaving half the world pipeline at scaled dimensions if validation fails.
            if (resizeEntity || resizeChain) {
                int maxTextureSize = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_MAX_TEXTURE_SIZE);
                if (width > maxTextureSize || height > maxTextureSize) {
                    throw new IllegalArgumentException("Requested auxiliary target exceeds GL_MAX_TEXTURE_SIZE: " + width + "x" + height);
                }
            }

            if (resizeEntity) entityTarget.resize(width, height, Minecraft.ON_OSX);

            // Use PostChain.resize rather than resizing its targets individually:
            // vanilla recalculates the chain's orthographic matrix in this method.
            if (resizeChain) entityEffect.resize(width, height);
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
}
