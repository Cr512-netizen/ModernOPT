package net.cr512.modernopt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.cr512.modernopt.mixin.RenderTargetAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Multisampled world target. Color/depth renderbuffers are resolved into a normal
 * texture target before sampling, so post-processing shaders never sample an MSAA texture.
 */
public final class MultisampleRenderTarget extends RenderTarget {
    private final int samples;
    private final boolean onOSX;
    private int multisampleFramebuffer = -1;
    private int multisampleColorBuffer = -1;
    private int multisampleDepthBuffer = -1;
    private TextureTarget resolvedTarget;

    public MultisampleRenderTarget(int width, int height, int samples, boolean onOSX) {
        super(true);
        if (samples != 2 && samples != 4 && samples != 8 && samples != 16) {
            throw new IllegalArgumentException("MSAA samples must be 2, 4, 8, or 16");
        }
        this.samples = samples;
        this.onOSX = onOSX;
        resize(width, height, onOSX);
    }

    public int samples() {
        return samples;
    }

    @Override
    public void createBuffers(int width, int height, boolean clearError) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid MSAA target size");
        resolvedTarget = new TextureTarget(width, height, true, onOSX);

        multisampleFramebuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, multisampleFramebuffer);

        multisampleColorBuffer = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, multisampleColorBuffer);
        GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, samples, GL11.GL_RGBA8, width, height);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, multisampleColorBuffer);

        multisampleDepthBuffer = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, multisampleDepthBuffer);
        GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, samples, GL30.GL_DEPTH_COMPONENT24, width, height);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, multisampleDepthBuffer);

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            destroyBuffers();
            throw new IllegalStateException("MSAA framebuffer is incomplete: 0x" + Integer.toHexString(status));
        }

        ((RenderTargetAccessor)(Object)this).modernopt$setFrameBufferId(multisampleFramebuffer);
        // The inherited IDs are only used by vanilla bookkeeping. Actual texture sampling
        // is routed through the resolved target by getColorTextureId/getDepthTextureId.
        ((RenderTargetAccessor)(Object)this).modernopt$setColorTextureId(resolvedTarget.getColorTextureId());
        ((RenderTargetAccessor)(Object)this).modernopt$setDepthBufferId(resolvedTarget.getDepthTextureId());
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
        if (clearError) GL11.glGetError();
    }

    @Override
    public void destroyBuffers() {
        if (multisampleColorBuffer >= 0) GL30.glDeleteRenderbuffers(multisampleColorBuffer);
        if (multisampleDepthBuffer >= 0) GL30.glDeleteRenderbuffers(multisampleDepthBuffer);
        if (multisampleFramebuffer >= 0) GL30.glDeleteFramebuffers(multisampleFramebuffer);
        multisampleColorBuffer = -1;
        multisampleDepthBuffer = -1;
        multisampleFramebuffer = -1;
        if (resolvedTarget != null) {
            resolvedTarget.destroyBuffers();
            resolvedTarget = null;
        }
        ((RenderTargetAccessor)(Object)this).modernopt$setFrameBufferId(-1);
        ((RenderTargetAccessor)(Object)this).modernopt$setColorTextureId(-1);
        ((RenderTargetAccessor)(Object)this).modernopt$setDepthBufferId(-1);
    }

    @Override
    public void bindWrite(boolean setViewport) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, multisampleFramebuffer);
        if (setViewport) RenderSystem.viewport(0, 0, width, height);
    }

    @Override
    public void setFilterMode(int filterMode) {
        if (resolvedTarget != null) resolvedTarget.setFilterMode(filterMode);
    }

    @Override
    public int getColorTextureId() {
        resolve();
        return resolvedTarget == null ? -1 : resolvedTarget.getColorTextureId();
    }

    @Override
    public int getDepthTextureId() {
        resolve();
        return resolvedTarget == null ? -1 : resolvedTarget.getDepthTextureId();
    }

    private void resolve() {
        if (resolvedTarget == null || multisampleFramebuffer < 0) return;
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, multisampleFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,
                ((RenderTargetAccessor)(Object)resolvedTarget).modernopt$getFrameBufferId());
            int mask = GL11.GL_COLOR_BUFFER_BIT;
            if (useDepth) mask |= GL11.GL_DEPTH_BUFFER_BIT;
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, mask, GL11.GL_NEAREST);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
        }
    }
}
