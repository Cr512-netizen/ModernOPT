// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;

import java.nio.IntBuffer;

/**
 * MSR2 history target.
 *
 * <ul>
 *   <li>Colour: RGBA16F (64 bits/pixel). Alpha is unused; the extra precision is for temporal
 *       accumulation, where R11G11B10F banded after a few frames.</li>
 *   <li>Depth: R16_UINT (16 bits/pixel), packed linear depth.</li>
 * </ul>
 *
 * <p>At 1080p each history set is about 20 MiB (16 MiB colour + 4 MiB depth), and the ping-pong
 * pair is about 40 MiB. The target is allocated at the output resolution: the temporal pass
 * dispatches one thread per output pixel, so the history keeps the detail that a
 * source-resolution history threw away before the spatial upscale.</p>
 *
 * <p>Ping-pong strategy eliminates read-after-write hazards:
 * Frame N: read from A, write to B
 * Frame N+1: read from B, write to A</p>
 *
 * <p><b>Important:</b> every framebuffer bind goes through
 * {@link GlStateManager#_glBindFramebuffer(int, int)}, not raw
 * {@code glBindFramebuffer}. Minecraft caches the currently bound framebuffer; binding
 * behind its back makes the following {@code main.bindWrite(true)} a no-op (the cache still
 * says "the main target is bound"), the spatial pass then writes into the history texture and
 * the real screen is never updated - the classic MSR black screen.</p>
 */
public final class MSR2HistoryTarget {

    /**
     * Internal format of the colour history. Shared by the texture allocation, both image
     * bindings in {@link RenderPipeline} and the {@code layout(...)} qualifier in
     * {@code msr_temporal.comp}; changing one without the others makes the compute pass fail.
     */
    public static final int COLOR_FORMAT = GL30C.GL_RGBA16F;

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

    private MSR2HistoryTarget() {}

    public static MSR2HistoryTarget create(int width, int height) {
        MSR2HistoryTarget target = new MSR2HistoryTarget();
        target.allocate(width, height);
        return target;
    }

    private void allocate(int width, int height) {
        this.width = width;
        this.height = height;

        RenderSystem.assertOnRenderThreadOrInit();

        // Create ping-pong FBOs with RGBA16F color + R16_UINT depth
        for (int i = 0; i < 2; i++) {
            int fbo = GL30C.glGenFramebuffers();
            int colorTex = createColorTexture();
            int depthTex = createDepthTexture();

            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                    GL11C.GL_TEXTURE_2D, colorTex, 0);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT1,
                    GL11C.GL_TEXTURE_2D, depthTex, 0);
            GL30C.glDrawBuffers(new int[] {
                    GL30C.GL_COLOR_ATTACHMENT0,
                    GL30C.GL_COLOR_ATTACHMENT1
            });

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

        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
    }

    private int createColorTexture() {
        int tex = GL11C.glGenTextures();
        GlStateManager._bindTexture(tex);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        // RGBA16F: 64 bits per pixel for temporal accumulation.
        GL30C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, COLOR_FORMAT, width, height, 0,
                GL11C.GL_RGBA, GL30C.GL_HALF_FLOAT, (IntBuffer) null);
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
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
        // The temporal pass writes BOTH the colour history and the depth history, so both
        // attachments have to be enabled. Leaving only COLOR_ATTACHMENT0 active means the
        // depth texture is never updated and the next frame's disocclusion test reads zeros.
        GL30C.glDrawBuffers(new int[] {
                GL30C.GL_COLOR_ATTACHMENT0,
                GL30C.GL_COLOR_ATTACHMENT1
        });
        GlStateManager._viewport(0, 0, width, height);
    }

    /** Binds the FBO without touching draw buffers (compute path uses image load/store). */
    public void bindWriteForCompute() {
        int fbo = useA ? fboB : fboA;
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
        GlStateManager._viewport(0, 0, width, height);
    }

    /** Brings back the default framebuffer through the state cache. */
    public void unbind() {
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
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
        unbind();
        if (fboA != 0) GL30C.glDeleteFramebuffers(fboA);
        if (fboB != 0) GL30C.glDeleteFramebuffers(fboB);
        if (colorTexA != 0) GL11C.glDeleteTextures(colorTexA);
        if (colorTexB != 0) GL11C.glDeleteTextures(colorTexB);
        if (depthTexA != 0) GL11C.glDeleteTextures(depthTexA);
        if (depthTexB != 0) GL11C.glDeleteTextures(depthTexB);
        fboA = fboB = colorTexA = colorTexB = depthTexA = depthTexB = 0;
    }
}
