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
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;

import java.io.IOException;

/**
 * FSR1 spatial upscaling: EASU scales the low-resolution world image to the
 * native framebuffer size; RCAS sharpens that result in a separate pass.
 */
public final class Fsr1Renderer {
    private static ShaderInstance easu;
    private static ShaderInstance rcas;
    private static TextureTarget intermediate;

    private Fsr1Renderer() {}

    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath("modernopt", "easu"),
                DefaultVertexFormat.POSITION_TEX), shader -> easu = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                ResourceLocation.fromNamespaceAndPath("modernopt", "rcas"),
                DefaultVertexFormat.POSITION_TEX), shader -> rcas = shader);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to register ModernOPT FSR1 shaders", exception);
        }
    }

    public static boolean render(RenderTarget input, RenderTarget output) {
        if (easu == null || rcas == null || input == null || output == null) return false;
        int width = output.width;
        int height = output.height;
        if (width <= 0 || height <= 0) return false;

        if (intermediate == null) {
            intermediate = new TextureTarget(width, height, true, Minecraft.ON_OSX);
        } else if (intermediate.width != width || intermediate.height != height) {
            intermediate.resize(width, height, Minecraft.ON_OSX);
        }

        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        boolean success = false;
        try {
            drawPass(input, intermediate, easu, width, height, true);
            drawPass(intermediate, output, rcas, width, height, false);
            success = true;
        } catch (RuntimeException | LinkageError exception) {
            // Fail closed: the caller can use the regular fullscreen blit.
            return false;
        } finally {
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
            output.bindWrite(true);
        }
        return success;
    }

    private static void drawPass(RenderTarget input, RenderTarget output, ShaderInstance shader,
                                 int width, int height, boolean hasOutputSize) {
        output.bindWrite(true);
        RenderSystem.viewport(0, 0, output.width, output.height);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        RenderSystem.setProjectionMatrix(
            new Matrix4f().setOrtho(0F, output.width, output.height, 0F, -1000F, 1000F),
            VertexSorting.ORTHOGRAPHIC_Z
        );
        shader.setSampler("InSampler", input.getColorTextureId());
        if (hasOutputSize) {
            var uniform = shader.getUniform("OutputSize");
            if (uniform == null) throw new IllegalStateException("FSR1 EASU OutputSize uniform is missing");
            uniform.set((float) width, (float) height);
        }
        RenderSystem.setShader(() -> shader);
        BufferBuilder builder = Tesselator.getInstance().begin(
            VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX
        );
        builder.addVertex(0F, 0F, 0F).setUv(0F, 0F);
        builder.addVertex(0F, output.height, 0F).setUv(0F, 1F);
        builder.addVertex(output.width, output.height, 0F).setUv(1F, 1F);
        builder.addVertex(output.width, 0F, 0F).setUv(1F, 0F);
        BufferUploader.drawWithShader(builder.build());
        shader.clear();
    }
}
