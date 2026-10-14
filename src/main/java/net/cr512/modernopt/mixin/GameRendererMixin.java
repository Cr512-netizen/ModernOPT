package net.cr512.modernopt.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.cr512.modernopt.render.Fsr1Renderer;
import net.cr512.modernopt.render.RenderScaleController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow(remap = false) @Final private Minecraft minecraft;

    @Unique private RenderTarget modernopt$originalTarget;
    @Unique private TextureTarget modernopt$scaledTarget;
    @Unique private Matrix4f modernopt$oldProjection;
    @Unique private VertexSorting modernopt$oldVertexSorting;
    @Unique private boolean modernopt$active;

    @Unique
    private static boolean modernopt$shaderPackInUse() {
        if (!net.neoforged.fml.ModList.get().isLoaded("iris")) return false;
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object result = apiClass.getMethod("isShaderPackInUse").invoke(api);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return true;
        }
    }

    @Inject(method = "renderLevel", at = @At("HEAD"), remap = false)
    private void modernopt$beginWorldPass(CallbackInfo ci) {
        this.modernopt$active = false;
        if (!RenderScaleController.isEnabled() || this.minecraft.level == null) return;
        if (modernopt$shaderPackInUse()) return;

        RenderTarget original = this.minecraft.getMainRenderTarget();
        int targetWidth = original.width;
        int targetHeight = original.height;
        int width = RenderScaleController.scaledWidth(targetWidth);
        int height = RenderScaleController.scaledHeight(targetHeight);
        if (targetWidth <= 0 || targetHeight <= 0 || width >= targetWidth && height >= targetHeight) return;

        this.modernopt$originalTarget = original;
        if (this.modernopt$scaledTarget == null) {
            this.modernopt$scaledTarget = new TextureTarget(width, height, true, Minecraft.ON_OSX);
        } else if (this.modernopt$scaledTarget.width != width || this.modernopt$scaledTarget.height != height) {
            this.modernopt$scaledTarget.resize(width, height, Minecraft.ON_OSX);
        }

        this.modernopt$scaledTarget.clear(Minecraft.ON_OSX);
        this.modernopt$scaledTarget.bindWrite(true);
        ((MinecraftRenderTargetMixin)(Object)this.minecraft).modernopt$setMainRenderTarget(this.modernopt$scaledTarget);
        this.modernopt$active = true;
    }

    @Inject(method = "renderLevel", at = @At("RETURN"), remap = false)
    private void modernopt$endWorldPass(CallbackInfo ci) {
        if (!this.modernopt$active) return;

        try {
            ((MinecraftRenderTargetMixin)(Object)this.minecraft).modernopt$setMainRenderTarget(this.modernopt$originalTarget);
            this.modernopt$originalTarget.bindWrite(true);
            int width = this.modernopt$originalTarget.width;
            int height = this.modernopt$originalTarget.height;
            RenderSystem.viewport(0, 0, width, height);

            this.modernopt$oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
            this.modernopt$oldVertexSorting = RenderSystem.getVertexSorting();
            RenderSystem.setProjectionMatrix(
                new Matrix4f().setOrtho(0.0F, width, height, 0.0F, -1000.0F, 1000.0F),
                VertexSorting.ORTHOGRAPHIC_Z
            );

            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

            // FSR1 is a two-pass EASU + RCAS upscale. If shader compilation or
            // setup fails, preserve a safe linear fullscreen fallback.
            boolean fsrRendered = Fsr1Renderer.render(this.modernopt$scaledTarget, this.modernopt$originalTarget);
            if (!fsrRendered) {
                RenderSystem.setShader(GameRenderer::getPositionTexShader);
                RenderSystem.setShaderTexture(0, this.modernopt$scaledTarget.getColorTextureId());
                BufferBuilder builder = Tesselator.getInstance().begin(
                    VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX
                );
                builder.addVertex(0.0F, 0.0F, 0.0F).setUv(0.0F, 0.0F);
                builder.addVertex(0.0F, height, 0.0F).setUv(0.0F, 1.0F);
                builder.addVertex(width, height, 0.0F).setUv(1.0F, 1.0F);
                builder.addVertex(width, 0.0F, 0.0F).setUv(1.0F, 0.0F);
                BufferUploader.drawWithShader(builder.build());
            }
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            if (this.modernopt$oldProjection != null) {
                RenderSystem.setProjectionMatrix(this.modernopt$oldProjection, this.modernopt$oldVertexSorting);
                this.modernopt$oldProjection = null;
                this.modernopt$oldVertexSorting = null;
            }
            ((MinecraftRenderTargetMixin)(Object)this.minecraft).modernopt$setMainRenderTarget(this.modernopt$originalTarget);
            this.modernopt$originalTarget.bindWrite(true);
            this.modernopt$active = false;
        }
    }
}
