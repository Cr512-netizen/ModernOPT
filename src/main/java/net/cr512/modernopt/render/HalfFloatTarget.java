package net.cr512.modernopt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;

import java.nio.IntBuffer;

/**
 * Colour-only render target with an {@code RGBA16F} attachment.
 *
 * <p>The vanilla {@link RenderTarget} always allocates {@code RGBA8}. FSR 2 accumulates the
 * image over many frames, so an 8 bit history would slowly quantise the result and show banding
 * in dark gradients. Half floats keep the accumulated colour stable. No depth attachment is
 * needed: the temporal pass only writes colour and stores the linear view depth in the alpha
 * channel for the next frame's disocclusion test.</p>
 */
public final class HalfFloatTarget extends RenderTarget {

    public HalfFloatTarget(int width, int height) {
        super(false);
        RenderSystem.assertOnRenderThreadOrInit();
        this.resize(width, height, false);
    }

    @Override
    public void createBuffers(int width, int height, boolean clear) {
        RenderSystem.assertOnRenderThreadOrInit();
        int maxSize = RenderSystem.maxSupportedTextureSize();
        if (width <= 0 || height <= 0 || width > maxSize || height > maxSize) {
            throw new IllegalArgumentException("History target " + width + "x" + height
                    + " is outside the supported texture size (max " + maxSize + ")");
        }

        this.viewWidth = width;
        this.viewHeight = height;
        this.width = width;
        this.height = height;
        this.frameBufferId = GlStateManager.glGenFramebuffers();
        this.colorTextureId = TextureUtil.generateTextureId();

        GlStateManager._bindTexture(this.colorTextureId);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GlStateManager._texImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA16F, width, height, 0,
                GL11C.GL_RGBA, GL30C.GL_HALF_FLOAT, (IntBuffer) null);
        this.filterMode = GL11C.GL_LINEAR;

        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.frameBufferId);
        GlStateManager._glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, this.colorTextureId, 0);
        this.checkStatus();

        this.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        this.clear(clear);
        this.unbindRead();
    }
}
