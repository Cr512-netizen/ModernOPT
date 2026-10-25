package net.cr512.modernopt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * First value of the buffer version counters below.
     *
     * <p>Iris mixins {@code Blaze3dRenderTargetExt} into {@link RenderTarget} and uses
     * {@code iris$getDepthBufferVersion()} to decide whether the depth texture it bound to its
     * own framebuffers is still the right one. The vanilla counter starts at 0 and is only
     * incremented when a target destroys its buffers, so the vanilla target and this one could
     * report the same small number. Swapping between two targets with equal versions makes Iris
     * keep drawing against a stale depth texture. Staying far above that range keeps the two
     * target instances distinguishable without touching Iris at all.</p>
     */
    private static final AtomicInteger IRIS_VERSION = new AtomicInteger(0x40000000);

    private static int nextIrisVersion() {
        return IRIS_VERSION.getAndIncrement();
    }

    private int irisDepthBufferVersion = nextIrisVersion();
    private int irisColorBufferVersion = nextIrisVersion();

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

        // Fresh depth/colour textures: Iris has to re-attach them to its framebuffers.
        this.irisDepthBufferVersion = nextIrisVersion();
        this.irisColorBufferVersion = nextIrisVersion();

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
        this.irisDepthBufferVersion = nextIrisVersion();
        this.irisColorBufferVersion = nextIrisVersion();
    }

    /**
     * {@code Blaze3dRenderTargetExt} member, added to {@link RenderTarget} by an Iris mixin at
     * runtime. Declaring it here is a plain override as far as the JVM is concerned and is
     * inert when Iris is not installed, so ModernOPT needs neither a compile nor a runtime
     * dependency on Iris.
     */
    public int iris$getDepthBufferVersion() {
        return this.irisDepthBufferVersion;
    }

    /** Counterpart of {@link #iris$getDepthBufferVersion()} for the colour attachment. */
    public int iris$getColorBufferVersion() {
        return this.irisColorBufferVersion;
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
        } else {
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, this.frameBufferId);
        }

        if (setViewport) {
            GlStateManager._viewport(0, 0, this.viewWidth, this.viewHeight);
        } else {
            this.repairStaleViewport();
        }
    }

    /**
     * Repairs the viewport after a {@code bindWrite(false)}.
     *
     * <p>Vanilla re-binds the world target with {@code setViewport = false} in the middle of the
     * frame, assuming the viewport is still the one it set before. If a window sized helper target
     * was bound in between, the viewport now describes the window instead of this target, and every
     * later pass (entities, hand, particles) is clipped to a corner. Only that exact case is
     * repaired, so deliberately smaller viewports (Iris composite passes) are left alone.</p>
     */
    private void repairStaleViewport() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return;
        }

        MemoryStack stack = MemoryStack.stackPush();
        try {
            IntBuffer viewport = stack.mallocInt(4);
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
            int width = viewport.get(2);
            int height = viewport.get(3);
            if (width == this.viewWidth && height == this.viewHeight) {
                return;
            }
            if (width != minecraft.getWindow().getWidth() || height != minecraft.getWindow().getHeight()) {
                return;
            }
        } finally {
            stack.pop();
        }

        GlStateManager._viewport(0, 0, this.viewWidth, this.viewHeight);
    }

    /**
     * True when this target (or its multisampled attachment) is the framebuffer OpenGL is
     * currently drawing into. Used to keep the GL viewport in sync with the scaled image: some
     * vanilla passes bind a full size target and leave its window sized viewport behind, and the
     * next pass then draws into the small target with coordinates twice as large as it can hold.
     */
    public boolean isCurrentDrawTarget() {
        if (this.frameBufferId < 0) {
            return false;
        }
        int bound = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        return bound == this.frameBufferId || this.msaa != null && bound == this.msaa.id();
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
