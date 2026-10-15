package net.cr512.modernopt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.cr512.modernopt.config.ModernOptConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;

import java.io.IOException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Spatial upscalers used by ModernOPT. */
public final class Fsr1Renderer {
    private static ShaderInstance easu;
    private static ShaderInstance rcas;
    private static ShaderInstance bilinear;
    private static ShaderInstance nearest;
    private static ShaderInstance softscale;
    private static ShaderInstance fxaa;
    private static ShaderInstance ssaaDownsample;
    private static TextureTarget intermediate;
    private static TextureTarget fxaaIntermediate;
    private static final Logger LOGGER = LoggerFactory.getLogger("ModernOPT");
    private static boolean renderFailureLogged;
    private static boolean fxaaFailureLogged;

    private Fsr1Renderer() {}

    /**
     * Register each shader independently. A shader compile failure must not abort
     * Minecraft's entire resource reload (which otherwise leaves the client stuck
     * before the title screen). Features that need a failed shader are skipped.
     */
    public static void registerShaders(RegisterShadersEvent event) {
        registerShader(event, "easu", shader -> easu = shader);
        registerShader(event, "rcas", shader -> rcas = shader);
        registerShader(event, "bilinear", shader -> bilinear = shader);
        registerShader(event, "nearest", shader -> nearest = shader);
        registerShader(event, "softscale", shader -> softscale = shader);
        registerShader(event, "fxaa", shader -> fxaa = shader);
        registerShader(event, "ssaa_downsample", shader -> ssaaDownsample = shader);
    }

    private static void registerShader(RegisterShadersEvent event, String name, Consumer<ShaderInstance> setter) {
        try {
            event.registerShader(
                new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath("modernopt", name),
                    DefaultVertexFormat.POSITION_TEX),
                setter
            );
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Could not load ModernOPT shader '{}'. Features requiring it will be unavailable; continuing resource loading.", name, exception);
            switch (name) {
                case "easu" -> easu = null;
                case "rcas" -> rcas = null;
                case "bilinear" -> bilinear = null;
                case "nearest" -> nearest = null;
                case "softscale" -> softscale = null;
                case "fxaa" -> fxaa = null;
                case "ssaa_downsample" -> ssaaDownsample = null;
                default -> { }
            }
        }
    }

    public static boolean render(RenderTarget input, RenderTarget output) {
        if (input == null || output == null) return false;
        int width = output.width;
        int height = output.height;
        if (width <= 0 || height <= 0) return false;

        int mode = ModernOptConfig.UPSCALER_MODE.get();
        // Mode 0 skips every ModernOPT post-process shader. The caller performs
        // one direct opaque blit to native resolution, avoiding extra texture reads,
        // shader invocations, and the FSR intermediate allocation.
        if (mode == 0) return false;

        // If FSR1 shaders are unavailable, fall back to bilinear rather than leaving
        // the world unresolved. The no-upscaler mode above remains shader-free.
        boolean useFsr1 = mode == 1 && easu != null && rcas != null;
        int effectiveMode = mode == 1 && !useFsr1 ? 2 : mode;
        ShaderInstance simpleShader = switch (effectiveMode) {
            case 2 -> bilinear;
            case 3 -> nearest;
            case 4 -> softscale;
            default -> null;
        };
        if (effectiveMode >= 2 && effectiveMode <= 4 && simpleShader == null) return false;
        if (mode < 0 || mode > 4) return false;

        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        try {
            // Allocation and resize are inside the guarded block: unsupported framebuffer
            // formats must fall back to the caller's safe blit instead of crashing the pass.
            if (useFsr1) {
                if (intermediate == null) {
                    intermediate = new TextureTarget(width, height, true, Minecraft.ON_OSX);
                } else if (intermediate.width != width || intermediate.height != height) {
                    intermediate.resize(width, height, Minecraft.ON_OSX);
                }
            }
            if (useFsr1) {
                drawPass(input, intermediate, easu, width, height, true);
                drawPass(intermediate, output, rcas, width, height, false);
            } else {
                drawPass(input, output, simpleShader, width, height, false);
            }
            return true;
        } catch (RuntimeException | LinkageError exception) {
            if (!renderFailureLogged) {
                renderFailureLogged = true;
                LOGGER.error("Spatial upscaler pass failed (mode={}, input={}x{}, output={}x{}); ModernOPT will use the safe fallback for subsequent frames.",
                    mode, input.width, input.height, output.width, output.height, exception);
            }
            return false;
        } finally {
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
            output.bindWrite(true);
        }
    }


    /**
     * Applies FXAA to an already-rendered world image. The intermediate target
     * prevents sampling from and rendering into the same color texture.
     */
    public static boolean applyFxaa(RenderTarget output) {
        if (output == null || fxaa == null || bilinear == null) return false;
        int width = output.width;
        int height = output.height;
        if (width <= 0 || height <= 0) return false;

        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        try {
            if (fxaaIntermediate == null) {
                fxaaIntermediate = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            } else if (fxaaIntermediate.width != width || fxaaIntermediate.height != height) {
                fxaaIntermediate.resize(width, height, Minecraft.ON_OSX);
            }
            drawPass(output, fxaaIntermediate, fxaa, width, height, false);
            // At native resolution bilinear sampling is a direct texel copy.
            drawPass(fxaaIntermediate, output, bilinear, width, height, false);
            return true;
        } catch (RuntimeException | LinkageError exception) {
            if (!fxaaFailureLogged) {
                fxaaFailureLogged = true;
                LOGGER.error("FXAA pass failed; this frame is left on the unfiltered image. This failure is logged only once.", exception);
            }
            return false;
        } finally {
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
            output.bindWrite(true);
            RenderSystem.viewport(0, 0, width, height);
        }
    }


    /** Downsamples a 2x-per-axis supersampled world image using a 2x2 box filter. */
    public static boolean downsampleSsaa(RenderTarget input, RenderTarget output) {
        if (input == null || output == null || ssaaDownsample == null) return false;
        if (input.width != output.width * 2 || input.height != output.height * 2) return false;
        int width = output.width;
        int height = output.height;
        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        try {
            drawPass(input, output, ssaaDownsample, width, height, false);
            return true;
        } catch (RuntimeException | LinkageError exception) {
            return false;
        } finally {
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
            output.bindWrite(true);
            RenderSystem.viewport(0, 0, width, height);
        }
    }

    /** Resolves an MSAA target and copies it to the native-size world target. */
    public static boolean copyResolved(RenderTarget input, RenderTarget output) {
        if (input == null || output == null || bilinear == null) return false;
        int width = output.width;
        int height = output.height;
        if (width <= 0 || height <= 0) return false;
        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        try {
            drawPass(input, output, bilinear, width, height, false);
            return true;
        } catch (RuntimeException | LinkageError exception) {
            return false;
        } finally {
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
            output.bindWrite(true);
            RenderSystem.viewport(0, 0, width, height);
        }
    }

    private static void drawPass(RenderTarget input, RenderTarget output, ShaderInstance shader,
                                 int width, int height, boolean hasOutputSize) {
        output.bindWrite(true);
        RenderSystem.viewport(0, 0, output.width, output.height);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0F, output.width, output.height, 0F, -1000F, 1000F), VertexSorting.ORTHOGRAPHIC_Z);
        shader.setSampler("InSampler", input.getColorTextureId());
        if (hasOutputSize) {
            var uniform = shader.getUniform("OutputSize");
            if (uniform == null) throw new IllegalStateException("FSR1 EASU OutputSize uniform is missing");
            uniform.set((float) width, (float) height);
        }
        RenderSystem.setShader(() -> shader);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // Framebuffer textures use OpenGL's bottom-left origin. The destination
        // quad uses GUI-style top-left coordinates, so flip V when sampling.
        builder.addVertex(0F, 0F, 0F).setUv(0F, 1F);
        builder.addVertex(0F, output.height, 0F).setUv(0F, 0F);
        builder.addVertex(output.width, output.height, 0F).setUv(1F, 0F);
        builder.addVertex(output.width, 0F, 0F).setUv(1F, 1F);
        BufferUploader.drawWithShader(builder.build());
        shader.clear();
    }
}
