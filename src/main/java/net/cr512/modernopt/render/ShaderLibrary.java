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

    private ShaderLibrary(ShaderInstance bilinear, ShaderInstance nearest, ShaderInstance soft,
                          ShaderInstance box, ShaderInstance fxaa, ShaderInstance easu, ShaderInstance rcas) {
        this.bilinear = bilinear;
        this.nearest = nearest;
        this.soft = soft;
        this.box = box;
        this.fxaa = fxaa;
        this.easu = easu;
        this.rcas = rcas;
    }

    public static ShaderLibrary load(ResourceProvider resources) throws IOException {
        return new ShaderLibrary(
                create(resources, "bilinear"),
                create(resources, "nearest"),
                create(resources, "soft"),
                create(resources, "box"),
                create(resources, "fxaa"),
                create(resources, "easu"),
                create(resources, "rcas"));
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
                && isProgram(this.rcas);
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
        GlStateManager._enableDepthTest();
        GlStateManager._enableBlend();
    }
}
