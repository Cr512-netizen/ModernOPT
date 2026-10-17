package net.cr512.modernopt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.cr512.modernopt.mixin.RenderTargetAccessor;
import net.minecraft.client.renderer.GameRenderer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared, state-restoring framebuffer copy helpers used by the world post-process stage. */
public final class FramebufferBlitter {
    private static final Logger LOGGER = LoggerFactory.getLogger("ModernOPT");

    private FramebufferBlitter() {}

    /**
     * Fixed-function color blit. For a multisample source OpenGL performs the resolve
     * directly into the native target, avoiding an intermediate texture resolve.
     */
    public static boolean blitFramebuffer(RenderTarget input, RenderTarget output) {
        if (input == null || output == null || input.width <= 0 || input.height <= 0
            || output.width <= 0 || output.height <= 0) return false;

        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,
                ((RenderTargetAccessor)(Object)input).modernopt$getFrameBufferId());
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,
                ((RenderTargetAccessor)(Object)output).modernopt$getFrameBufferId());
            GL30.glBlitFramebuffer(
                0, 0, input.width, input.height,
                0, 0, output.width, output.height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST
            );
            // Do not call glGetError: it can synchronize the driver and consumes errors
            // that may belong to another rendering mod.
            return true;
        } catch (RuntimeException | LinkageError exception) {
            LOGGER.warn("Direct framebuffer copy failed; falling back to the textured blit.", exception);
            return false;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            output.bindWrite(true);
            RenderSystem.viewport(0, 0, output.width, output.height);
        }
    }

    /** Textured fallback used only when the fixed-function framebuffer copy fails. */
    public static void blitOpaque(RenderTarget input, RenderTarget output) {
        int width = output.width;
        int height = output.height;
        output.bindWrite(true);
        RenderSystem.viewport(0, 0, width, height);
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, input.getColorTextureId());
        BufferBuilder builder = Tesselator.getInstance().begin(
            VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX
        );
        // Framebuffer textures are bottom-left-origin; the destination quad is top-left-origin.
        builder.addVertex(0.0F, 0.0F, 0.0F).setUv(0.0F, 1.0F);
        builder.addVertex(0.0F, height, 0.0F).setUv(0.0F, 0.0F);
        builder.addVertex(width, height, 0.0F).setUv(1.0F, 0.0F);
        builder.addVertex(width, 0.0F, 0.0F).setUv(1.0F, 1.0F);
        BufferUploader.drawWithShader(builder.build());
    }
}
