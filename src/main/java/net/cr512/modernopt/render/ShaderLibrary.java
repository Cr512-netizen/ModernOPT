// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.cr512.modernopt.ModernOPT;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import com.mojang.blaze3d.shaders.Uniform;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.io.IOException;

/**
 * Owns the full-screen shader passes used by the upscaler chain and draws them.
 *
 * <p>Every pass is a plain {@link ShaderInstance} built from
 * {@code assets/modernopt/shaders/core/*.json}. All of them consume the
 * {@link DefaultVertexFormat#BLIT_SCREEN} format, i.e. four vertices with positions in the
 * {@code 0..1} range, which the vertex shader maps straight to NDC.</p>
 */
public final class ShaderLibrary {

    public final ShaderInstance bilinear;
    public final ShaderInstance nearest;
    public final ShaderInstance soft;
    public final ShaderInstance box;
    public final ShaderInstance fxaa;
    public final ShaderInstance easu;
    public final ShaderInstance rcas;
    public final ShaderInstance blit;
    /** Generic Lanczos reconstruction, usable with any spatial upscaler. */
    public final ShaderInstance lanczos;
    /** {@code null} means this driver/program failed to link; MSR then falls back to FSR 1. */
    public final ShaderInstance temporal;
    
    // MSR2 spatial fallback shader (the temporal pass is a raw compute program)
    public final ShaderInstance msrSpatial;

    private ShaderLibrary(ShaderInstance bilinear, ShaderInstance nearest, ShaderInstance soft,
                          ShaderInstance box, ShaderInstance fxaa, ShaderInstance easu, ShaderInstance rcas,
                          ShaderInstance blit, ShaderInstance lanczos, ShaderInstance temporal,
                          ShaderInstance msrSpatial) {
        this.bilinear = bilinear;
        this.nearest = nearest;
        this.soft = soft;
        this.box = box;
        this.fxaa = fxaa;
        this.easu = easu;
        this.rcas = rcas;
        this.blit = blit;
        this.lanczos = lanczos;
        this.temporal = temporal;
        this.msrSpatial = msrSpatial;
    }

    public static ShaderLibrary load(ResourceProvider resources) throws IOException {
        ShaderInstance temporal = null;
        try {
            temporal = create(resources, "temporal");
        } catch (Throwable throwable) {
            // Keep every other upscaler working when only the temporal pass is unsupported.
            ModernOPT.LOG.error("[ModernOPT] MSR temporal shader failed to compile, MSR will use the FSR 1 path",
                    throwable);
        }
        
        // Generic Lanczos reconstruction. A failure must not take the other upscalers down.
        ShaderInstance lanczos = null;
        try {
            lanczos = create(resources, "lanczos");
        } catch (Throwable throwable) {
            ModernOPT.LOG.error("[ModernOPT] Lanczos shader failed to compile; Lanczos will be unavailable",
                    throwable);
        }

        // MSR2 spatial fallback shader
        ShaderInstance msrSpatial = null;
        try {
            msrSpatial = create(resources, "msr_spatial");
        } catch (Throwable throwable) {
            ModernOPT.LOG.error("[ModernOPT] MSR2 spatial shader failed to compile, MSR2 will be unavailable",
                    throwable);
        }

        return new ShaderLibrary(
                create(resources, "bilinear"),
                create(resources, "nearest"),
                create(resources, "soft"),
                create(resources, "box"),
                create(resources, "fxaa"),
                create(resources, "easu"),
                create(resources, "rcas"),
                create(resources, "blit"),
                lanczos,
                temporal,
                msrSpatial);
    }

    private static ShaderInstance create(ResourceProvider resources, String name) throws IOException {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(ModernOPT.MOD_ID, name);
        return new ShaderInstance(resources, location, DefaultVertexFormat.BLIT_SCREEN);
    }

    public void close() {
        this.bilinear.close();
        this.nearest.close();
        this.soft.close();
        this.box.close();
        this.fxaa.close();
        this.easu.close();
        this.rcas.close();
        this.blit.close();
        if (this.lanczos != null) {
            this.lanczos.close();
        }
        if (this.temporal != null) {
            this.temporal.close();
        }
        if (this.msrSpatial != null) {
            this.msrSpatial.close();
        }
    }

    /** True when the temporal accumulation pass is available. */
    public boolean hasTemporal() {
        return isProgram(this.temporal);
    }

    /** True when MSR2 shaders are available. */
    public boolean hasMsr() {
        return isProgram(this.msrSpatial);
    }

    /** True when the generic Lanczos reconstruction shader is available. */
    public boolean hasLanczos() {
        return isProgram(this.lanczos);
    }

    /**
     * True when every pass still owns a linked GL program. Vanilla closes all shader programs on
     * a resource reload (F3+T), so the library is rebuilt when this returns {@code false}.
     */
    public boolean isValid() {
        return isProgram(this.bilinear)
                && isProgram(this.nearest)
                && isProgram(this.soft)
                && isProgram(this.box)
                && isProgram(this.fxaa)
                && isProgram(this.easu)
                && isProgram(this.rcas)
                && isProgram(this.blit);
    }

    private static boolean isProgram(@Nullable ShaderInstance shader) {
        return shader != null && shader.getId() != -1 && org.lwjgl.opengl.GL20C.glIsProgram(shader.getId());
    }

    /**
     * Draws one full-screen pass. The caller is responsible for having bound the destination
     * framebuffer and its viewport.
     *
     * @param source        colour texture to sample
     * @param sourceWidth   width of {@code source}
     * @param sourceHeight  height of {@code source}
     * @param outputWidth   destination width (for texel-size maths inside the shader)
     * @param outputHeight  destination height
     * @param param0        filter specific parameter, e.g. softness or sharpness, {@code 0..1}
     * @param param1        reserved, always {@code 0} for now
     */
    public static void draw(ShaderInstance shader, int source, int sourceWidth, int sourceHeight,
                            int outputWidth, int outputHeight, float param0, float param1) {
        RenderSystem.assertOnRenderThread();

        shader.setSampler("InputSampler", source);

        Uniform inputSize = shader.getUniform("InputSize");
        if (inputSize != null) {
            inputSize.set((float) sourceWidth, (float) sourceHeight);
        }
        Uniform outputSize = shader.getUniform("OutputSize");
        if (outputSize != null) {
            outputSize.set((float) outputWidth, (float) outputHeight);
        }
        Uniform params = shader.getUniform("Params");
        if (params != null) {
            params.set(param0, param1, (float) sourceWidth, (float) sourceHeight);
        }

        shader.apply();

        BufferBuilder buffer = RenderSystem.renderThreadTesselator()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
        buffer.addVertex(0.0F, 0.0F, 0.0F);
        buffer.addVertex(1.0F, 0.0F, 0.0F);
        buffer.addVertex(1.0F, 1.0F, 0.0F);
        buffer.addVertex(0.0F, 1.0F, 0.0F);
        BufferUploader.draw(buffer.buildOrThrow());

        shader.clear();
    }

    /**
     * Draws the generic Lanczos pass with scene depth bound. The depth sampler lets the kernel
     * reject taps from a different surface at silhouettes, which removes the dark halo that a
     * depth-blind Lanczos kernel leaves around foreground objects.
     */
    public static void drawLanczos(ShaderInstance shader, int source, int depth,
                                   int sourceWidth, int sourceHeight,
                                   int outputWidth, int outputHeight,
                                   float radius, boolean depthValid) {
        RenderSystem.assertOnRenderThread();

        shader.setSampler("InputSampler", source);
        shader.setSampler("DepthSampler", depth);
        shader.setSampler("LanczosTable", RenderPipeline.ensureLanczosTable());
        Uniform inputSize = shader.getUniform("InputSize");
        if (inputSize != null) {
            inputSize.set((float) sourceWidth, (float) sourceHeight);
        }
        Uniform params = shader.getUniform("Params");
        if (params != null) {
            params.set(radius, 0.0F, (float) sourceWidth, (float) sourceHeight);
        }
        Uniform depthValidUniform = shader.getUniform("DepthValid");
        if (depthValidUniform != null) {
            depthValidUniform.set(depth != 0 && depthValid ? 1.0F : 0.0F);
        }

        shader.apply();

        BufferBuilder buffer = RenderSystem.renderThreadTesselator()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
        buffer.addVertex(0.0F, 0.0F, 0.0F);
        buffer.addVertex(1.0F, 0.0F, 0.0F);
        buffer.addVertex(1.0F, 1.0F, 0.0F);
        buffer.addVertex(0.0F, 1.0F, 0.0F);
        BufferUploader.draw(buffer.buildOrThrow());

        shader.clear();
    }

    /**
     * One-texel bilinear copy. Used by the MSR2 spatial pass when the upscale factor is small
     * enough that the fused EASU+RCAS sharpening costs more than it is worth.
     */
    public static void drawBlit(ShaderInstance shader, int source, int sourceWidth, int sourceHeight,
                                int outputWidth, int outputHeight) {
        RenderSystem.assertOnRenderThread();
        shader.setSampler("Source", source);
        shader.apply();

        BufferBuilder buffer = RenderSystem.renderThreadTesselator()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
        buffer.addVertex(0.0F, 0.0F, 0.0F);
        buffer.addVertex(1.0F, 0.0F, 0.0F);
        buffer.addVertex(1.0F, 1.0F, 0.0F);
        buffer.addVertex(0.0F, 1.0F, 0.0F);
        BufferUploader.draw(buffer.buildOrThrow());

        shader.clear();
    }

    /**
     * Runs the temporal accumulation pass.
     *
     * <p>Unlike {@link #draw}, this pass has three samplers and a set of camera matrices, and it
     * writes the linear view depth into alpha, so alpha writes are enabled for the duration of
     * the draw.</p>
     */
    public static void drawTemporal(ShaderInstance shader,
                                    int colorTexture, int historyTexture, int depthTexture,
                                    int inputWidth, int inputHeight, int outputWidth, int outputHeight,
                                    Matrix4f inverseProjection, Matrix4f previousProjection,
                                    Matrix4f viewRotation, Matrix4f previousViewRotation,
                                    float cameraDeltaX, float cameraDeltaY, float cameraDeltaZ,
                                    float jitterX, float jitterY,
                                    boolean historyValid, boolean depthValid, boolean irisDepth,
                                    float baseAlpha, float reactiveStrength, float depthTolerance) {
        RenderSystem.assertOnRenderThread();

        shader.setSampler("ColorSampler", colorTexture);
        shader.setSampler("HistorySampler", historyTexture);
        shader.setSampler("DepthSampler", depthTexture);

        setFloat2(shader, "InputSize", inputWidth, inputHeight);
        setFloat2(shader, "OutputSize", outputWidth, outputHeight);
        setFloat2(shader, "Jitter", jitterX, jitterY);
        setFloat3(shader, "CameraDelta", cameraDeltaX, cameraDeltaY, cameraDeltaZ);
        setMatrix(shader, "ViewRotation", viewRotation);
        setMatrix(shader, "PrevViewRotation", previousViewRotation);
        setMatrix(shader, "InvProjection", inverseProjection);
        setMatrix(shader, "PrevProjection", previousProjection);
        Uniform params = shader.getUniform("Params");
        if (params != null) {
            params.set(baseAlpha, reactiveStrength, depthTolerance, 0.0F);
        }
        Uniform valid = shader.getUniform("HistoryValid");
        if (valid != null) {
            valid.set(historyValid ? 1.0F : 0.0F);
        }
        Uniform depth = shader.getUniform("DepthValid");
        if (depth != null) {
            depth.set(depthValid ? 1.0F : 0.0F);
        }
        // When using Iris depth (native resolution = output size), DepthScale = 1.0
        // When using scaled depth (internal resolution), DepthScale = inputWidth / outputWidth
        float depthScale = irisDepth ? 1.0F : (float) inputWidth / (float) outputWidth;
        Uniform depthScaleUniform = shader.getUniform("DepthScale");
        if (depthScaleUniform != null) {
            depthScaleUniform.set(depthScale);
        }

        shader.apply();
        GlStateManager._colorMask(true, true, true, true);

        BufferBuilder buffer = RenderSystem.renderThreadTesselator()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
        buffer.addVertex(0.0F, 0.0F, 0.0F);
        buffer.addVertex(1.0F, 0.0F, 0.0F);
        buffer.addVertex(1.0F, 1.0F, 0.0F);
        buffer.addVertex(0.0F, 1.0F, 0.0F);
        BufferUploader.draw(buffer.buildOrThrow());

        shader.clear();
        GlStateManager._colorMask(true, true, true, false);
    }

    private static void setFloat2(ShaderInstance shader, String name, float x, float y) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(x, y);
        }
    }

    private static void setFloat3(ShaderInstance shader, String name, float x, float y, float z) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(x, y, z);
        }
    }

    private static void setMatrix(ShaderInstance shader, String name, Matrix4f matrix) {
        Uniform uniform = shader.getUniform(name);
        if (uniform != null) {
            uniform.set(matrix);
        }
    }

    /** Common GL state used by every reconstruction pass. */
    public static void beginPassState() {
        GlStateManager._colorMask(true, true, true, false);
        GlStateManager._disableDepthTest();
        GlStateManager._disableBlend();
        GlStateManager._disableScissorTest();
        GlStateManager._disableCull();
        GlStateManager._depthMask(false);
    }

    /** Restores the state the GUI pass expects right after the reconstruction. */
    public static void endPassState() {
        GlStateManager._depthMask(true);
        GlStateManager._colorMask(true, true, true, true);
        // Iris/Sodium leave their own depth compare behind (often GL_GREATER for the reversed
        // depth setup they use internally). Vanilla's GUI assumes the default GL_LEQUAL, so
        // without this reset the hand and every HUD layer fail the depth test against the
        // main target and the screen ends up world-only.
        GlStateManager._depthFunc(org.lwjgl.opengl.GL11C.GL_LEQUAL);
        GlStateManager._enableDepthTest();
        GlStateManager._enableBlend();
    }
}
