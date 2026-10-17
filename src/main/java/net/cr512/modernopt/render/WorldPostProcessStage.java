package net.cr512.modernopt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.mixin.MinecraftRenderTargetMixin;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The final world-image composition stage. This extraction deliberately keeps
 * the current pass ordering unchanged; later renderer work can test and replace
 * individual passes without expanding GameRendererMixin further.
 */
public final class WorldPostProcessStage {
    private static final Logger LOGGER = LoggerFactory.getLogger("ModernOPT");

    private WorldPostProcessStage() {}

    public static void execute(
        Minecraft minecraft,
        RenderTarget originalTarget,
        RenderTarget worldTarget,
        boolean active,
        boolean scalingActive,
        boolean ssaaActive,
        boolean msaaActive,
        boolean shaderPackInUse
    ) {
        if (minecraft == null || originalTarget == null) return;

        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        boolean depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        try {
            ((MinecraftRenderTargetMixin)(Object)minecraft).modernopt$setMainRenderTarget(originalTarget);
            originalTarget.bindWrite(true);
            int width = originalTarget.width;
            int height = originalTarget.height;
            RenderSystem.viewport(0, 0, width, height);

            RenderSystem.setProjectionMatrix(
                new Matrix4f().setOrtho(0.0F, width, height, 0.0F, -1000.0F, 1000.0F),
                VertexSorting.ORTHOGRAPHIC_Z
            );

            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

            WorldUpscalePass.execute(
                worldTarget, originalTarget, active, scalingActive, ssaaActive, msaaActive, shaderPackInUse
            );
        } catch (RuntimeException | LinkageError passFailure) {
            LOGGER.warn("World post-process failed; attempting a safe framebuffer copy for this frame.", passFailure);
            if (worldTarget != null && active) {
                try {
                    FramebufferBlitter.blitOpaque(worldTarget, originalTarget);
                } catch (RuntimeException | LinkageError fallbackFailure) {
                    LOGGER.warn("Safe framebuffer fallback also failed; the native target is left for vanilla recovery.", fallbackFailure);
                }
            }
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.depthMask(depthMask);
            if (depthEnabled) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (blendEnabled) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
            ((MinecraftRenderTargetMixin)(Object)minecraft).modernopt$setMainRenderTarget(originalTarget);
            originalTarget.bindWrite(true);
            RenderSystem.viewport(0, 0, originalTarget.width, originalTarget.height);
        }
    }
}
