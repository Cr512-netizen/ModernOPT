package net.cr512.modernopt.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.cr512.modernopt.ModernOPT;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/**
 * Multisampled colour+depth renderbuffers used as the world render destination when MSAA is
 * enabled. Only the colour buffer is resolved back into the (single sampled) scaled target:
 * the depth buffer is not sampled by anything in our pipeline because Fabulous mode and Iris
 * shader packs force MSAA off (see {@link RenderPipeline}).
 */
final class MsaaFramebuffer {

    private final int framebuffer;
    private final int colorRenderbuffer;
    private final int depthRenderbuffer;
    private final int samples;
    private final int width;
    private final int height;

    private MsaaFramebuffer(int framebuffer, int colorRenderbuffer, int depthRenderbuffer,
                            int samples, int width, int height) {
        this.framebuffer = framebuffer;
        this.colorRenderbuffer = colorRenderbuffer;
        this.depthRenderbuffer = depthRenderbuffer;
        this.samples = samples;
        this.width = width;
        this.height = height;
    }

    /**
     * @return a working multisampled framebuffer, or {@code null} when the driver cannot give
     *         us at least {@code requestedSamples} samples (the caller then falls back to a
     *         plain single sampled target)
     */
    static MsaaFramebuffer create(int width, int height, int requestedSamples) {
        RenderSystem.assertOnRenderThreadOrInit();
        int maxSamples = GL11C.glGetInteger(GL30C.GL_MAX_SAMPLES);
        int samples = Math.min(Math.max(1, requestedSamples), Math.max(1, maxSamples));
        if (samples <= 1 || width <= 0 || height <= 0) {
            return null;
        }

        int framebuffer = GlStateManager.glGenFramebuffers();
        int color = GL30C.glGenRenderbuffers();
        int depth = GL30C.glGenRenderbuffers();

        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);

        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, color);
        GL30C.glRenderbufferStorageMultisample(GL30C.GL_RENDERBUFFER, samples, GL11C.GL_RGBA8, width, height);
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_RENDERBUFFER, color);

        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, depth);
        GL30C.glRenderbufferStorageMultisample(GL30C.GL_RENDERBUFFER, samples, GL30C.GL_DEPTH_COMPONENT24, width, height);
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_RENDERBUFFER, depth);

        int status = GlStateManager.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);

        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, 0);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);

        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            GL30C.glDeleteRenderbuffers(color);
            GL30C.glDeleteRenderbuffers(depth);
            GlStateManager._glDeleteFramebuffers(framebuffer);
            ModernOPT.LOG.warn("[ModernOPT] MSAA framebuffer incomplete (status {}), falling back to no MSAA", status);
            return null;
        }

        return new MsaaFramebuffer(framebuffer, color, depth, samples, width, height);
    }

    int id() {
        return this.framebuffer;
    }

    int samples() {
        return this.samples;
    }

    int width() {
        return this.width;
    }

    int height() {
        return this.height;
    }

    /** Blits the multisampled colour buffer into the given single sampled framebuffer. */
    void resolveTo(int targetFramebuffer) {
        RenderSystem.assertOnRenderThreadOrInit();
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, this.framebuffer);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, targetFramebuffer);
        GlStateManager._glBlitFrameBuffer(0, 0, this.width, this.height, 0, 0, this.width, this.height,
                GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
    }

    /** Clears the multisampled buffers with the given colour, mirroring {@code RenderTarget.clear}. */
    void clear(float red, float green, float blue, float alpha) {
        RenderSystem.assertOnRenderThreadOrInit();
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.framebuffer);
        GlStateManager._clearColor(red, green, blue, alpha);
        GlStateManager._clearDepth(1.0D);
        GlStateManager._clear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT, true);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
    }

    void delete() {
        RenderSystem.assertOnRenderThreadOrInit();
        GL30C.glDeleteRenderbuffers(this.colorRenderbuffer);
        GL30C.glDeleteRenderbuffers(this.depthRenderbuffer);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
        GlStateManager._glDeleteFramebuffers(this.framebuffer);
    }
}
