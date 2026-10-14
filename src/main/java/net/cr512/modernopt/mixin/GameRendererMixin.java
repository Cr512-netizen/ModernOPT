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
import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.render.Fsr1Renderer;
import net.cr512.modernopt.render.MultisampleRenderTarget;
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
    @Unique private MultisampleRenderTarget modernopt$msaaTarget;
    @Unique private RenderTarget modernopt$worldTarget;
    @Unique private Matrix4f modernopt$oldProjection;
    @Unique private VertexSorting modernopt$oldVertexSorting;
    @Unique private boolean modernopt$active;
    @Unique private boolean modernopt$scalingActive;
    @Unique private boolean modernopt$msaaActive;
    @Unique private boolean modernopt$ssaaActive;
    @Unique private boolean modernopt$fxaaOnly;
    @Unique private int modernopt$allocatedSamples;

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

    @Unique
    private static int modernopt$requestedSamples() {
        return switch (ModernOptConfig.MSAA_SAMPLES.get()) {
            case 2 -> 2;
            case 4 -> 4;
            case 8 -> 8;
            case 16 -> 16;
            default -> 0;
        };
    }

    @Inject(method = "renderLevel", at = @At("HEAD"), remap = false)
    private void modernopt$beginWorldPass(CallbackInfo ci) {
        this.modernopt$active = false;
        this.modernopt$scalingActive = false;
        this.modernopt$msaaActive = false;
        this.modernopt$ssaaActive = false;
        this.modernopt$fxaaOnly = false;
        this.modernopt$worldTarget = null;

        if (!ModernOptConfig.ENABLED.get() || this.minecraft.level == null) return;
        // Keep the existing safe bypass: don't replace targets underneath Iris' shader pipeline.
        if (modernopt$shaderPackInUse()) return;

        boolean ssaaRequested = ModernOptConfig.SSAA_ENABLED.get();
        boolean scalingRequestedInitially = !ssaaRequested && RenderScaleController.isEnabled();
        boolean scalingRequested = scalingRequestedInitially;
        boolean fxaaRequested = ModernOptConfig.FXAA_ENABLED.get();
        int samples = modernopt$requestedSamples();
        if (!scalingRequested && !ssaaRequested && !fxaaRequested && samples == 0) return;

        RenderTarget original = this.minecraft.getMainRenderTarget();
        int targetWidth = original.width;
        int targetHeight = original.height;
        if (targetWidth <= 0 || targetHeight <= 0) return;
        this.modernopt$originalTarget = original;

        // Targets with stencil attachments are left untouched for compatibility with mods.
        if (samples > 0 && original.isStencilEnabled()) samples = 0;

        // SSAA doubles each dimension. Respect the actual GPU texture limit before allocating.
        int maxTextureSize = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_MAX_TEXTURE_SIZE);
        if (ssaaRequested && (targetWidth > maxTextureSize / 2 || targetHeight > maxTextureSize / 2)) {
            ssaaRequested = false;
            scalingRequested = RenderScaleController.isEnabled();
        }
        if (ssaaRequested) samples = 0;
        int width = ssaaRequested ? targetWidth * 2 : (scalingRequested ? RenderScaleController.scaledWidth(targetWidth) : targetWidth);
        int height = ssaaRequested ? targetHeight * 2 : (scalingRequested ? RenderScaleController.scaledHeight(targetHeight) : targetHeight);
        this.modernopt$ssaaActive = ssaaRequested;
        this.modernopt$scalingActive = width != targetWidth || height != targetHeight;

        boolean needsOffscreen = this.modernopt$scalingActive || samples > 0;
        if (!needsOffscreen) {
            this.modernopt$fxaaOnly = fxaaRequested;
            if (!this.modernopt$fxaaOnly) this.modernopt$originalTarget = null;
            return;
        }

        if (samples > 0) {
            int requestedSamples = samples;
            try {
                if (this.modernopt$msaaTarget == null || this.modernopt$allocatedSamples != samples) {
                    if (this.modernopt$msaaTarget != null) this.modernopt$msaaTarget.destroyBuffers();
                    this.modernopt$msaaTarget = null;
                    try {
                        this.modernopt$msaaTarget = new MultisampleRenderTarget(width, height, samples, Minecraft.ON_OSX);
                    } catch (RuntimeException | LinkageError requestedModeUnavailable) {
                        if (samples != 16) throw requestedModeUnavailable;
                        // 16x is optional hardware support; retry with the broadly supported 8x mode.
                        samples = 8;
                        this.modernopt$msaaTarget = new MultisampleRenderTarget(width, height, samples, Minecraft.ON_OSX);
                    }
                    // Track the requested mode, not the fallback target, to avoid retrying 16x every frame.
                    this.modernopt$allocatedSamples = requestedSamples;
                } else if (this.modernopt$msaaTarget.width != width || this.modernopt$msaaTarget.height != height) {
                    this.modernopt$msaaTarget.resize(width, height, Minecraft.ON_OSX);
                }
                this.modernopt$worldTarget = this.modernopt$msaaTarget;
                this.modernopt$msaaActive = true;
            } catch (RuntimeException | LinkageError msaaUnavailable) {
                // Unsupported sample counts/framebuffer formats must not break world rendering.
                if (this.modernopt$msaaTarget != null) this.modernopt$msaaTarget.destroyBuffers();
                this.modernopt$msaaTarget = null;
                this.modernopt$allocatedSamples = 0;
                this.modernopt$msaaActive = false;
                this.modernopt$worldTarget = null;
            }
        }

        if (this.modernopt$worldTarget == null && this.modernopt$scalingActive) {
            if (this.modernopt$scaledTarget == null) {
                this.modernopt$scaledTarget = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            } else if (this.modernopt$scaledTarget.width != width || this.modernopt$scaledTarget.height != height) {
                this.modernopt$scaledTarget.resize(width, height, Minecraft.ON_OSX);
            }
            this.modernopt$worldTarget = this.modernopt$scaledTarget;
        }

        if (this.modernopt$worldTarget == null) {
            this.modernopt$originalTarget = null;
            this.modernopt$scalingActive = false;
            this.modernopt$msaaActive = false;
            this.modernopt$fxaaOnly = fxaaRequested;
            return;
        }

        this.modernopt$worldTarget.clear(Minecraft.ON_OSX);
        this.modernopt$worldTarget.bindWrite(true);
        ((MinecraftRenderTargetMixin)(Object)this.minecraft).modernopt$setMainRenderTarget(this.modernopt$worldTarget);
        this.modernopt$active = true;
    }

    @Inject(method = "renderLevel", at = @At("RETURN"), remap = false)
    private void modernopt$endWorldPass(CallbackInfo ci) {
        if (!this.modernopt$active && !this.modernopt$fxaaOnly) return;

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

            if (this.modernopt$active && this.modernopt$scalingActive) {
                boolean upscaled = this.modernopt$ssaaActive
                    ? Fsr1Renderer.downsampleSsaa(this.modernopt$worldTarget, this.modernopt$originalTarget)
                    : Fsr1Renderer.render(this.modernopt$worldTarget, this.modernopt$originalTarget);
                if (!upscaled) {
                    RenderSystem.setShader(GameRenderer::getPositionTexShader);
                    RenderSystem.setShaderTexture(0, this.modernopt$worldTarget.getColorTextureId());
                    BufferBuilder builder = Tesselator.getInstance().begin(
                        VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX
                    );
                    builder.addVertex(0.0F, 0.0F, 0.0F).setUv(0.0F, 0.0F);
                    builder.addVertex(0.0F, height, 0.0F).setUv(0.0F, 1.0F);
                    builder.addVertex(width, height, 0.0F).setUv(1.0F, 1.0F);
                    builder.addVertex(width, 0.0F, 0.0F).setUv(1.0F, 0.0F);
                    BufferUploader.drawWithShader(builder.build());
                }
            } else if (this.modernopt$active && this.modernopt$msaaActive) {
                Fsr1Renderer.copyResolved(this.modernopt$worldTarget, this.modernopt$originalTarget);
            }

            if (ModernOptConfig.FXAA_ENABLED.get()) {
                Fsr1Renderer.applyFxaa(this.modernopt$originalTarget);
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
            this.modernopt$scalingActive = false;
            this.modernopt$msaaActive = false;
            this.modernopt$ssaaActive = false;
            this.modernopt$fxaaOnly = false;
            this.modernopt$originalTarget = null;
            this.modernopt$worldTarget = null;
        }
    }
}
