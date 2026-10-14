package net.cr512.modernopt.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
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
    @Shadow @Final private Minecraft minecraft;
    @Unique private RenderTarget modernopt$originalTarget;
    @Unique private TextureTarget modernopt$scaledTarget;
    @Unique private Matrix4f modernopt$oldProjection;
    @Unique private boolean modernopt$active;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void modernopt$beginWorldPass(CallbackInfo ci) {
        this.modernopt$active = false;
        if (!RenderScaleController.isEnabled() || this.minecraft.level == null) return;
        // Iris owns a custom shader pipeline; don't swap its target without an explicit bridge.
        if (net.neoforged.fml.ModList.get().isLoaded("iris")) return;

        RenderTarget original = this.minecraft.getMainRenderTarget();
        int windowWidth = this.minecraft.getWindow().getWidth();
        int windowHeight = this.minecraft.getWindow().getHeight();
        int width = RenderScaleController.scaledWidth(windowWidth);
        int height = RenderScaleController.scaledHeight(windowHeight);
        if (width >= windowWidth && height >= windowHeight) return;

        this.modernopt$originalTarget = original;
        if (this.modernopt$scaledTarget == null) {
            this.modernopt$scaledTarget = new TextureTarget(width, height, true);
        } else if (this.modernopt$scaledTarget.width != width || this.modernopt$scaledTarget.height != height) {
            this.modernopt$scaledTarget.resize(width, height, Minecraft.ON_OSX);
        }
        this.modernopt$scaledTarget.clear(Minecraft.ON_OSX);
        this.modernopt$scaledTarget.bindWrite(true);
        ((MinecraftRenderTargetMixin)(Object)this.minecraft).modernopt$setMainRenderTarget(this.modernopt$scaledTarget);
        this.modernopt$active = true;
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void modernopt$endWorldPass(CallbackInfo ci) {
        if (!this.modernopt$active) return;
        try {
            ((MinecraftRenderTargetMixin)(Object)this.minecraft).modernopt$setMainRenderTarget(this.modernopt$originalTarget);
            this.modernopt$originalTarget.bindWrite(true);
            int width = this.minecraft.getWindow().getWidth();
            int height = this.minecraft.getWindow().getHeight();
            RenderSystem.viewport(0, 0, width, height);
            this.modernopt$oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0.0F, width, height, 0.0F, -1000.0F, 1000.0F), ProjectionType.ORTHOGRAPHIC);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, this.modernopt$scaledTarget.getColorTextureId());
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            builder.addVertex(0.0F, 0.0F, 0.0F).setUv(0.0F, 0.0F);
            builder.addVertex(0.0F, height, 0.0F).setUv(0.0F, 1.0F);
            builder.addVertex(width, height, 0.0F).setUv(1.0F, 1.0F);
            builder.addVertex(width, 0.0F, 0.0F).setUv(1.0F, 0.0F);
            BufferUploader.drawWithShader(builder.build());
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            if (this.modernopt$oldProjection != null) {
                RenderSystem.setProjectionMatrix(this.modernopt$oldProjection, ProjectionType.PERSPECTIVE);
            }
            ((MinecraftRenderTargetMixin)(Object)this.minecraft).modernopt$setMainRenderTarget(this.modernopt$originalTarget);
            this.modernopt$originalTarget.bindWrite(true);
            this.modernopt$active = false;
        }
    }
}
