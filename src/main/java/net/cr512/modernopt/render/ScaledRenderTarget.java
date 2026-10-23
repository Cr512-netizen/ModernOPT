package net.cr512.modernopt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/**
 * Off-screen target the world is rendered into while render scaling is active.
 *
 * <p>It is a regular single sampled {@link RenderTarget} (colour + depth texture) so vanilla
 * and modded code that samples the depth buffer keeps working, plus an optional multisampled
 * framebuffer that the world is really drawn into. {@link #bindWrite(boolean)} redirects to the
 * multisampled buffers, {@link #resolveMsaa()} brings the colour back into the texture the
 * upscaler samples.</p>
 */
public final class ScaledRenderTarget extends RenderTarget {

    private MsaaFramebuffer msaa;
    private int requestedSamples = 1;

    public ScaledRenderTarget(int width, int height, int samples) {
        super(true);
        this.requestedSamples = Math.max(1, samples);
        this.resize(width, height, Minecraft.ON_OSX);
    }

    public int requestedSamples() {
        return this.requestedSamples;
    }

    /** Number of samples actually in use (1 when MSAA is off or unsupported). */
    public int activeSamples() {
        return this.msaa != null ? this.msaa.samples() : 1;
    }

    public boolean isMultisampled() {
        return this.msaa != null;
    }

    @Override
    public void createBuffers(int width, int height, boolean clear) {
        super.createBuffers(width, height, clear);

        if (this.msaa != null) {
            this.msaa.delete();
            this.msaa = null;
        }

        if (this.requestedSamples > 1) {
            this.msaa = MsaaFramebuffer.create(width, height, this.requestedSamples);
            if (this.msaa != null) {
                this.msaa.clear(1.0F, 1.0F, 1.0F, 0.0F);
            }
        }
    }

    @Override
    public void destroyBuffers() {
        if (this.msaa != null) {
            this.msaa.delete();
            this.msaa = null;
        }
        super.destroyBuffers();
    }

    @Override
    public void bindWrite(boolean setViewport) {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(() -> this.bindWriteInternal(setViewport));
        } else {
            this.bindWriteInternal(setViewport);
        }
    }

    private void bindWriteInternal(boolean setViewport) {
        RenderSystem.assertOnRenderThreadOrInit();
        if (this.msaa != null) {
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.msaa.id());
            if (setViewport) {
                GlStateManager._viewport(0, 0, this.viewWidth, this.viewHeight);
            }
        } else {
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.frameBufferId);
            if (setViewport) {
                GlStateManager._viewport(0, 0, this.viewWidth, this.viewHeight);
            }
        }
    }

    /** Resolves multisampled colour into {@link #getColorTextureId()} when MSAA is active. */
    public void resolveMsaa() {
        if (this.msaa != null) {
            this.msaa.resolveTo(this.frameBufferId);
        }
    }

    /** Nearest sampling is only interesting for the "nearest" upscaler. */
    public void setLinearFilter(boolean linear) {
        int mode = linear ? GL11C.GL_LINEAR : GL11C.GL_NEAREST;
        if (mode != this.filterMode) {
            this.setFilterMode(mode);
        }
    }
}
