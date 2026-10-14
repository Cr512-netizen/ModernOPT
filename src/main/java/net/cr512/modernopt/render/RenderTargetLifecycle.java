package net.cr512.modernopt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            PostChain entityEffect = minecraft.levelRenderer.entityEffect;
            if (entityEffect != null && entityEffect.fullSizedTargets != null) {
                for (RenderTarget target : entityEffect.fullSizedTargets) {
                    resizeIfNeeded(target, width, height);
                }
            }
            return true;
        } catch (RuntimeException | LinkageError exception) {
            LOGGER.warn("Could not synchronize world auxiliary targets to {}x{}; ModernOPT will bypass scaled rendering for this frame.", width, height, exception);
            return false;
        }
    }

    private static void resizeIfNeeded(RenderTarget target, int width, int height) {
        if (target != null && (target.width != width || target.height != height)) {
            target.resize(width, height, Minecraft.ON_OSX);
        }
    }
}
