package net.cr512.modernopt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.mixin.RenderTargetAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Spatial upscalers used by ModernOPT. */
public final class Fsr1Renderer {
    private static ShaderInstance easu;
    private static ShaderInstance easuNative;
    private static ShaderInstance easuFp16;
    private static ShaderInstance rcasFp16;
    private static ShaderInstance rcas;
    private static ShaderInstance bilinear;
    private static ShaderInstance nearest;
    private static ShaderInstance softscale;
    private static ShaderInstance fxaa;
    private static ShaderInstance ssaaDownsample;
    private static TextureTarget intermediate;
    // Keep immutable fullscreen geometry on the GPU; rebuilding/uploading a quad for each FSR pass
    // is avoidable CPU and driver work on every frame.
    private static VertexBuffer fullscreenTriangle;
    private static boolean fullscreenTriangleUnavailable;
    private static boolean fullscreenTriangleFailureLogged;
    private static final Logger LOGGER = LoggerFactory.getLogger("ModernOPT");
    private static boolean renderFailureLogged;
    // Avoid retrying a shader path that has already thrown every frame; a mode change or resource reload permits retry.
    private static int failedUpscalerMode = -1;
    private static int lastRequestedUpscalerMode = -1;
    private static boolean fxaaFailureLogged;
    private static boolean nativeEasuFailed;
    private static boolean nativeEasuFailureLogged;
    private static boolean fp16FsrFailed;
    private static boolean fp16FsrFailureLogged;
    private static String diagnosticPath = "not yet selected";
    private static String lastLoggedDiagnosticPath = "";

    private Fsr1Renderer() {}

    /**
     * Register each shader independently. A shader compile failure must not abort
     * Minecraft's entire resource reload (which otherwise leaves the client stuck
     * before the title screen). Features that need a failed shader are skipped.
     */
    public static void registerShaders(RegisterShadersEvent event) {
        failedUpscalerMode = -1;
        lastRequestedUpscalerMode = -1;
        renderFailureLogged = false;
        nativeEasuFailed = false;
        nativeEasuFailureLogged = false;
        fp16FsrFailed = false;
        fp16FsrFailureLogged = false;
        registerShader(event, "easu", shader -> easu = shader);
        // Native textureGather is substantially cheaper than emulating each gather
        // with four texelFetch calls. If the driver/mod shader pipeline rejects it,
        // the explicit-gather shader above remains the compatibility fallback.
        registerShader(event, "easu_native", shader -> easuNative = shader);
        // Optional fast path for GPUs exposing native OpenGL FP16 arithmetic.
        // Other GPUs continue using the portable FP32 shaders.
        String fp16Suffix = fp16ShaderSuffix();
        if (fp16Suffix != null) {
            registerShader(event, "easu" + fp16Suffix, shader -> easuFp16 = shader);
            registerShader(event, "rcas" + fp16Suffix, shader -> rcasFp16 = shader);
            if (easuFp16 != null && rcasFp16 != null) {
                LOGGER.info("FP16 FSR1 shaders are available for extension {}; they will be used when FSR1 is selected.",
                    fp16Suffix.equals("_fp16_amd") ? "GL_AMD_gpu_shader_half_float" : "GL_NV_gpu_shader5");
            } else {
                LOGGER.info("FP16 FSR1 shaders could not be loaded; the existing FP32 path remains active.");
            }
        } else {
            easuFp16 = null;
            rcasFp16 = null;
            LOGGER.debug("Native OpenGL FP16 arithmetic is unavailable; using the portable FP32 FSR1 path.");
        }
        registerShader(event, "rcas", shader -> rcas = shader);
        registerShader(event, "bilinear", shader -> bilinear = shader);
        registerShader(event, "nearest", shader -> nearest = shader);
        registerShader(event, "softscale", shader -> softscale = shader);
        registerShader(event, "fxaa", shader -> fxaa = shader);
        registerShader(event, "ssaa_downsample", shader -> ssaaDownsample = shader);
    }

    /** Release ModernOPT-owned scratch GPU resources while the client GL context is alive. */
    public static void releaseResources() {
        if (intermediate != null) {
            try {
                intermediate.destroyBuffers();
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.warn("Could not release the upscaler scratch target during shutdown.", failure);
            } finally {
                intermediate = null;
            }
        }
        if (fullscreenTriangle != null) {
            try {
                fullscreenTriangle.close();
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.warn("Could not release the cached fullscreen triangle during shutdown.", failure);
            } finally {
                fullscreenTriangle = null;
            }
        }
        fullscreenTriangleUnavailable = false;
        fullscreenTriangleFailureLogged = false;
    }

    public static String diagnosticStatus() {
        String capability = !ModernOptConfig.FSR1_FP16_ENABLED.get() ? "FP16 disabled by config"
            : (easuFp16 != null && rcasFp16 != null ? "FP16 shaders loaded" : "FP16 unavailable");
        if (fp16FsrFailed) capability = "FP16 runtime failure; FP32 fallback latched";
        return capability + "; selectedPath=" + diagnosticPath;
    }

    private static void setDiagnosticPath(String path) {
        diagnosticPath = path;
        if (!path.equals(lastLoggedDiagnosticPath)) {
            lastLoggedDiagnosticPath = path;
            LOGGER.info("Render diagnostic: selected upscaler path={}", path);
        }
    }

    /**
     * Select a shader source with an explicit extension directive. GLSL extension
     * names are not portable preprocessor feature macros, so detecting them with
     * #if defined(GL_...) inside one shared shader can incorrectly reject capable GPUs.
     */
    private static String fp16ShaderSuffix() {
        try {
            var caps = GL.getCapabilities();
            if (caps.GL_AMD_gpu_shader_half_float && caps.GL_AMD_gpu_shader_int16) return "_fp16_amd";
            if (caps.GL_NV_gpu_shader5) return "_fp16";
            return null;
        } catch (IllegalStateException noCurrentContext) {
            // Some launch paths can register shaders before a context is current.
            return null;
        }
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
                case "easu_native" -> easuNative = null;
                case "easu_fp16", "easu_fp16_amd" -> easuFp16 = null;
                case "rcas_fp16", "rcas_fp16_amd" -> rcasFp16 = null;
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
        if (mode != lastRequestedUpscalerMode) {
            lastRequestedUpscalerMode = mode;
            failedUpscalerMode = -1;
        }
        if (mode == failedUpscalerMode) return false;
        // Mode 0 skips every ModernOPT post-process shader. The caller performs
        // one direct opaque blit to native resolution, avoiding extra texture reads,
        // shader invocations, and the FSR intermediate allocation.
        if (mode == 0) { setDiagnosticPath("mode=none; caller should direct-blit"); return false; }

        // If FSR1 shaders are unavailable, fall back to bilinear rather than leaving
        // the world unresolved. The no-upscaler mode above remains shader-free.
        ShaderInstance easuShader = easuNative != null && !nativeEasuFailed ? easuNative : easu;
        boolean useFp16Fsr = mode == 1 && ModernOptConfig.FSR1_FP16_ENABLED.get()
            && !fp16FsrFailed && easuFp16 != null && rcasFp16 != null;
        boolean useFsr1 = mode == 1 && (useFp16Fsr || (easuShader != null && rcas != null));
        int effectiveMode = mode == 1 && !useFsr1 ? 2 : mode;
        ShaderInstance simpleShader = switch (effectiveMode) {
            case 2 -> bilinear;
            case 3 -> nearest;
            case 4 -> softscale;
            default -> null;
        };
        if (effectiveMode >= 2 && effectiveMode <= 4 && simpleShader == null) {
            // Do not retry a mode with a shader that failed registration on every frame.
            // Resource reload or changing the selected mode resets this latch.
            failedUpscalerMode = mode;
            setDiagnosticPath("mode=" + mode + " failed; safe blit fallback");
            if (!renderFailureLogged) {
                renderFailureLogged = true;
                LOGGER.error("Selected spatial upscaler has no loaded shader (mode={}, effectiveMode={}); using the safe fallback.", mode, effectiveMode);
            }
            return false;
        }
        if (mode < 0 || mode > 4) { setDiagnosticPath("invalid mode=" + mode); return false; }
        if (useFp16Fsr) setDiagnosticPath("FSR1 FP16 EASU+RCAS");
        else if (useFsr1) setDiagnosticPath("FSR1 FP32 " + (easuShader == easuNative ? "native textureGather" : "explicit texelFetch"));
        else setDiagnosticPath("spatial mode=" + effectiveMode + " (" + (simpleShader == null ? "missing shader" : "simple shader") + ")");

        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        try {
            // Allocation and resize are inside the guarded block: unsupported framebuffer
            // formats must fall back to the caller's safe blit instead of crashing the pass.
            if (useFsr1) {
                if (intermediate == null) {
                    intermediate = new TextureTarget(width, height, false, Minecraft.ON_OSX);
                } else if (intermediate.width != width || intermediate.height != height) {
                    intermediate.resize(width, height, Minecraft.ON_OSX);
                }
            }
            if (useFsr1) {
                if (useFp16Fsr) {
                    try {
                        drawPass(input, intermediate, easuFp16, width, height, true);
                        drawPass(intermediate, output, rcasFp16, width, height, false);
                    } catch (RuntimeException | LinkageError fp16Failure) {
                        // FP16 is an optimization, never a requirement. Fall back on
                        // this frame and disable the path for the current resource load.
                        fp16FsrFailed = true;
                        setDiagnosticPath("FSR1 FP16 failed; attempting FP32 fallback");
                        if (!fp16FsrFailureLogged) {
                            fp16FsrFailureLogged = true;
                            LOGGER.warn("FP16 FSR1 path failed at runtime; falling back to FP32.", fp16Failure);
                        }
                        if (easuShader != null && rcas != null) {
                            drawFsr1Fp32(input, output, width, height, easuShader);
                            setDiagnosticPath("FSR1 FP32 fallback after FP16 failure");
                        } else if (bilinear != null) {
                            drawPass(input, output, bilinear, width, height, false);
                            setDiagnosticPath("bilinear fallback after FP16 failure");
                        } else {
                            throw fp16Failure;
                        }
                    }
                } else {
                    drawFsr1Fp32(input, output, width, height, easuShader);
                }
            } else {
                drawPass(input, output, simpleShader, width, height, false);
            }
            return true;
        } catch (RuntimeException | LinkageError exception) {
            failedUpscalerMode = mode;
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


    private static void drawFsr1Fp32(RenderTarget input, RenderTarget output, int width, int height,
                                      ShaderInstance easuShader) {
        try {
            drawPass(input, intermediate, easuShader, width, height, true);
        } catch (RuntimeException | LinkageError nativeFailure) {
            // Some drivers accept textureGather during registration but fail when
            // first used. Keep the explicit-fetch shader as a compatibility fallback.
            if (easuShader != easuNative || easu == null) throw nativeFailure;
            nativeEasuFailed = true;
            if (!nativeEasuFailureLogged) {
                nativeEasuFailureLogged = true;
                LOGGER.warn("Native-gather FSR1 shader failed during rendering; switching to the explicit-fetch compatibility shader.", nativeFailure);
            }
            drawPass(input, intermediate, easu, width, height, true);
        }
        drawPass(intermediate, output, rcas, width, height, false);
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
            // FSR1 and FXAA run sequentially, never concurrently. Reuse the same
            // full-resolution scratch target instead of allocating a second color buffer.
            // The scratch target is always distinct from output, so this avoids feedback.
            if (intermediate == null) {
                intermediate = new TextureTarget(width, height, false, Minecraft.ON_OSX);
            } else if (intermediate.width != width || intermediate.height != height) {
                intermediate.resize(width, height, Minecraft.ON_OSX);
            }
            drawPass(output, intermediate, fxaa, width, height, false);
            // The FXAA output already has the same dimensions as the destination.
            // Copy it with a framebuffer blit rather than launching a redundant full-screen shader pass.
            if (!blitColor(intermediate, output)) return false;
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

    /** Copies equal-sized color buffers without allocating a shader pass. */
    private static boolean blitColor(RenderTarget input, RenderTarget output) {
        if (input == null || output == null || input.width <= 0 || input.height <= 0
            || input.width != output.width || input.height != output.height) return false;

        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,
                ((RenderTargetAccessor) (Object) input).modernopt$getFrameBufferId());
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,
                ((RenderTargetAccessor) (Object) output).modernopt$getFrameBufferId());
            GL30.glBlitFramebuffer(
                0, 0, input.width, input.height,
                0, 0, output.width, output.height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST
            );
            return true;
        } catch (RuntimeException | LinkageError exception) {
            return false;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            output.bindWrite(true);
            RenderSystem.viewport(0, 0, output.width, output.height);
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
        shader.setSampler("InSampler", input.getColorTextureId());
        if (hasOutputSize) {
            var uniform = shader.getUniform("OutputSize");
            if (uniform == null) throw new IllegalStateException("FSR1 EASU OutputSize uniform is missing");
            uniform.set((float) width, (float) height);
        }

        if (!fullscreenTriangleUnavailable) {
            try {
                if (fullscreenTriangle == null || fullscreenTriangle.isInvalid()) {
                    fullscreenTriangle = createFullscreenTriangle();
                }
                // A normalized oversized triangle covers the viewport without a seam.
                // This lets the same static VBO serve every output resolution.
                RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(0F, 1F, 1F, 0F, -1000F, 1000F),
                    VertexSorting.ORTHOGRAPHIC_Z
                );
                RenderSystem.setShader(() -> shader);
                fullscreenTriangle.drawWithShader(RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), shader);
                return;
            } catch (RuntimeException | LinkageError exception) {
                fullscreenTriangleUnavailable = true;
                if (fullscreenTriangle != null) {
                    try { fullscreenTriangle.close(); } catch (RuntimeException ignored) { }
                    fullscreenTriangle = null;
                }
                if (!fullscreenTriangleFailureLogged) {
                    fullscreenTriangleFailureLogged = true;
                    LOGGER.warn("Cached fullscreen triangle is unavailable; falling back to the immediate quad path.", exception);
                }
            }
        }

        // Compatibility fallback for drivers where the persistent VertexBuffer path fails.
        RenderSystem.setProjectionMatrix(
            new Matrix4f().setOrtho(0F, output.width, output.height, 0F, -1000F, 1000F),
            VertexSorting.ORTHOGRAPHIC_Z
        );
        RenderSystem.setShader(() -> shader);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.addVertex(0F, 0F, 0F).setUv(0F, 1F);
        builder.addVertex(0F, output.height, 0F).setUv(0F, 0F);
        builder.addVertex(output.width, output.height, 0F).setUv(1F, 0F);
        builder.addVertex(output.width, 0F, 0F).setUv(1F, 1F);
        BufferUploader.drawWithShader(builder.build());
        shader.clear();
    }

    private static VertexBuffer createFullscreenTriangle() {
        RenderSystem.assertOnRenderThread();
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        try {
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX);
            builder.addVertex(0F, 0F, 0F).setUv(0F, 1F);
            builder.addVertex(0F, 2F, 0F).setUv(0F, -1F);
            builder.addVertex(2F, 0F, 0F).setUv(2F, 1F);
            try (MeshData mesh = builder.build()) {
                buffer.upload(mesh);
            }
            return buffer;
        } catch (RuntimeException | LinkageError exception) {
            buffer.close();
            throw exception;
        }
    }
}
