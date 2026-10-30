package net.cr512.modernopt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;

import java.nio.IntBuffer;

/**
 * MSR 2.0 History Target using memory-efficient formats:
 * - Color: R11G11B10F (32 bits/pixel, no alpha)
 * - Depth: R16_UINT (packed linear depth, 16 bits/pixel)
 * 
 * Total: 48 bits/pixel vs 64 (RGBA16F) or 128 (RGBA32F)
 * At 1080p: ~12 MB for ping-pong pair (color + depth)
 * 
 * Ping-pong strategy eliminates read-after-write hazards:
 * Frame N: read from A, write to B
 * Frame N+1: read from B, write to A
 */
public final class MSRHistoryTarget {

    private int width;
    private int height;

    // Ping-pong buffers
    private int colorTexA = 0;
    private int colorTexB = 0;
    private int depthTexA = 0;
    private int depthTexB = 0;
    private int fboA = 0;
    private int fboB = 0;

    private boolean useA = true; // true = read from A, write to B

    private MSRHistoryTarget() {}

    public static MSRHistoryTarget create(int width, int height) {
        MSRHistoryTarget target = new MSRHistoryTarget();
        target.allocate(width, height);
        return target;
    }

    private void allocate(int width, int height) {
        this.width = width;
        this.height = height;

        RenderSystem.assertOnRenderThreadOrInit();

        // Create ping-pong FBOs with R11G11B10F color + R16_UINT depth
        for (int i = 0; i < 2; i++) {
            int fbo = GL30C.glGenFramebuffers();
            int colorTex = createColorTexture();
            int depthTex = createDepthTexture();

            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D, colorTex, 0);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT1,
                    GL11C.GL_TEXTURE_2D, depthTex, 0);

            int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
            if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("MSR history framebuffer incomplete: 0x" + Integer.toHexString(status));
            }

            if (i == 0) {
                fboA = fbo;
                colorTexA = colorTex;
                depthTexA = depthTex;
            } else {
                fboB = fbo;
                colorTexB = colorTex;
                depthTexB = depthTex;
            }
        }

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
    }

    private int createColorTexture() {
        int tex = GL11C.glGenTextures();
        GlStateManager._bindTexture(tex);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        // R11G11B10F = 32 bits, no alpha channel
        GL30C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_R11F_G11F_B10F, width, height, 0,
                GL11C.GL_RGB, GL33C.GL_UNSIGNED_INT_10F_11F_11F_REV, (IntBuffer) null);
        return tex;
    }

    private int createDepthTexture() {
        int tex = GL11C.glGenTextures();
        GlStateManager._bindTexture(tex);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        // R16_UINT = 16 bits, packed linear depth
        GL30C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_R16UI, width, height, 0,
                GL30C.GL_RED_INTEGER, GL11C.GL_UNSIGNED_SHORT, (IntBuffer) null);
        return tex;
    }

    /**
     * Binds the write target (ping-pong destination).
     * Call before rendering the temporal pass output.
     */
    public void bindWrite() {
        int fbo = useA ? fboB : fboA;
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
        GL11C.glViewport(0, 0, width, height);
    }

    /**
     * Swaps ping-pong buffers after temporal pass completes.
     * Call after temporal pass, before spatial pass.
     */
    public void swap() {
        useA = !useA;
    }

    /** @return color texture to read for spatial pass (source of temporal pass) */
    public int getReadColorTexture() {
        return useA ? colorTexA : colorTexB;
    }

    /** @return depth texture to read for spatial pass */
    public int getReadDepthTexture() {
        return useA ? depthTexA : depthTexB;
    }

    /** @return color texture to write (destination of temporal pass) */
    public int getWriteColorTexture() {
        return useA ? colorTexB : colorTexA;
    }

    /** @return depth texture to write (destination of temporal pass) */
    public int getWriteDepthTexture() {
        return useA ? depthTexB : depthTexA;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public void resize(int newWidth, int newHeight) {
        if (newWidth == width && newHeight == height) return;
        destroy();
        allocate(newWidth, newHeight);
    }

    public void destroy() {
        if (fboA != 0) GL30C.glDeleteFramebuffers(fboA);
        if (fboB != 0) GL30C.glDeleteFramebuffers(fboB);
        if (colorTexA != 0) GL11C.glDeleteTextures(colorTexA);
        if (colorTexB != 0) GL11C.glDeleteTextures(colorTexB);
        if (depthTexA != 0) GL11C.glDeleteTextures(depthTexA);
        if (depthTexB != 0) GL11C.glDeleteTextures(depthTexB);
        fboA = fboB = colorTexA = colorTexB = depthTexA = depthTexB = 0;
    }
}
