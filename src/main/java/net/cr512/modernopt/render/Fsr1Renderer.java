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

/** Spatial upscalers used by ModernOPT. */
public final class Fsr1Renderer {
    private static ShaderInstance easu;
    private static ShaderInstance rcas;
    private static ShaderInstance bilinear;
    private static ShaderInstance nearest;
    private static ShaderInstance softscale;
    private static TextureTarget intermediate;

    private Fsr1Renderer() {}

    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("modernopt", "easu"), DefaultVertexFormat.POSITION_TEX), shader -> easu = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("modernopt", "rcas"), DefaultVertexFormat.POSITION_TEX), shader -> rcas = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("modernopt", "bilinear"), DefaultVertexFormat.POSITION_TEX), shader -> bilinear = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("modernopt", "nearest"), DefaultVertexFormat.POSITION_TEX), shader -> nearest = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("modernopt", "softscale"), DefaultVertexFormat.POSITION_TEX), shader -> softscale = shader);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to register ModernOPT upscaler shaders", exception);
        }
    }

    public static boolean render(RenderTarget input, RenderTarget output) {
        if (input == null || output == null) return false;
        int width = output.width;
        int height = output.height;
        if (width <= 0 || height <= 0) return false;

        int mode = ModernOptConfig.UPSCALER_MODE.get();
        ShaderInstance simpleShader = switch (mode) {
            case 1 -> bilinear;
            case 2 -> nearest;
            case 3 -> softscale;
            default -> null;
        };
        if (mode == 0 && (easu == null || rcas == null)) return false;
        if (mode >= 1 && mode <= 3 && simpleShader == null) return false;
        if (mode < 0 || mode > 3) return false;

        if (mode == 0) {
            if (intermediate == null) {
                intermediate = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            } else if (intermediate.width != width || intermediate.height != height) {
                intermediate.resize(width, height, Minecraft.ON_OSX);
            }
        }

        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        try {
            if (mode == 0) {
                drawPass(input, intermediate, easu, width, height, true);
                drawPass(intermediate, output, rcas, width, height, false);
            } else {
                drawPass(input, output, simpleShader, width, height, false);
            }
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
        builder.addVertex(0F, 0F, 0F).setUv(0F, 0F);
        builder.addVertex(0F, output.height, 0F).setUv(0F, 1F);
        builder.addVertex(output.width, output.height, 0F).setUv(1F, 1F);
        builder.addVertex(output.width, 0F, 0F).setUv(1F, 0F);
        BufferUploader.drawWithShader(builder.build());
        shader.clear();
    }
}
