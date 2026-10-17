package net.cr512.modernopt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.mixin.MinecraftRenderTargetMixin;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

/**
 * The final world-image composition stage. This extraction deliberately keeps
 * the current pass ordering unchanged; later renderer work can test and replace
 * individual passes without expanding GameRendererMixin further.
 */
public final class WorldPostProcessStage {
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
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
            ((MinecraftRenderTargetMixin)(Object)minecraft).modernopt$setMainRenderTarget(originalTarget);
            originalTarget.bindWrite(true);
            RenderSystem.viewport(0, 0, originalTarget.width, originalTarget.height);
        }
    }
}
