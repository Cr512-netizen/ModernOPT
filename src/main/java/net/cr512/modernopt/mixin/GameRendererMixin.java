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
import net.cr512.modernopt.render.RenderDiagnostics;
import net.cr512.modernopt.render.MultisampleRenderTarget;
import net.cr512.modernopt.render.RenderScaleController;
import net.cr512.modernopt.render.RenderScalePhase;
import net.cr512.modernopt.render.RenderTargetLifecycle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Unique private static final Logger MODERNOPT_LOGGER = LoggerFactory.getLogger("ModernOPT");

    @Shadow @Final private Minecraft minecraft;

    @Unique private RenderTarget modernopt$originalTarget;
    @Unique private TextureTarget modernopt$scaledTarget;
    @Unique private MultisampleRenderTarget modernopt$msaaTarget;
    @Unique private RenderTarget modernopt$worldTarget;
    @Unique private Matrix4f modernopt$oldProjection;
    @Unique private VertexSorting modernopt$oldVertexSorting;
    @Unique private boolean modernopt$active;
    @Unique private boolean modernopt$shaderPackInUseThisFrame;
    @Unique private boolean modernopt$scalingActive;
    @Unique private boolean modernopt$msaaActive;
    @Unique private boolean modernopt$ssaaActive;
    @Unique private boolean modernopt$fxaaOnly;
    @Unique private boolean modernopt$auxTargetsAdjusted;
    @Unique private int modernopt$allocatedSamples;
    @Unique private int modernopt$failedMsaaRequest;

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

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void modernopt$beginWorldPass(CallbackInfo ci) {
        RenderDiagnostics.beginFrame();
        this.modernopt$shaderPackInUseThisFrame = false;
        RenderScalePhase.end();
        this.modernopt$active = false;
        this.modernopt$scalingActive = false;
        this.modernopt$msaaActive = false;
        this.modernopt$ssaaActive = false;
        this.modernopt$fxaaOnly = false;
        this.modernopt$worldTarget = null;
        RenderScaleController.update(this.minecraft.getFps());

        RenderTarget nativeTarget = this.minecraft.getMainRenderTarget();
        int nativeWidth = nativeTarget.width;
        int nativeHeight = nativeTarget.height;
        if (!ModernOptConfig.ENABLED.get() || this.minecraft.level == null) {
            modernopt$restoreAuxiliaryTargets(nativeWidth, nativeHeight);
            return;
        }
        // Shader packs provide their own temporal/spatial AA. Do not run ModernOPT
        // AA over them, but keep the spatial render-scale/upscaler path available.
        // Iris' target integration remains best-effort; if target synchronization fails,
        // the existing rollback below returns to native rendering for that frame.
        this.modernopt$shaderPackInUseThisFrame = modernopt$shaderPackInUse();
        boolean shaderPackInUse = this.modernopt$shaderPackInUseThisFrame;

        boolean ssaaRequested = !shaderPackInUse && ModernOptConfig.SSAA_ENABLED.get();
        boolean fxaaRequested = !shaderPackInUse && !ssaaRequested && ModernOptConfig.FXAA_ENABLED.get();
        // AA modes are exclusive: SSAA takes priority, then FXAA, then MSAA.
        // Shader packs disable only ModernOPT's AA modes, not its upscaler.
        int samples = (shaderPackInUse || ssaaRequested || fxaaRequested) ? 0 : modernopt$requestedSamples();
        boolean scalingRequestedInitially = !ssaaRequested && RenderScaleController.isEnabled();
        boolean scalingRequested = scalingRequestedInitially;
        if (samples == this.modernopt$failedMsaaRequest) samples = 0;
        if (!scalingRequested && !ssaaRequested && !fxaaRequested && samples == 0) {
            modernopt$restoreAuxiliaryTargets(nativeWidth, nativeHeight);
            return;
        }

        RenderTarget original = nativeTarget;
        int targetWidth = nativeWidth;
        int targetHeight = nativeHeight;
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
        this.modernopt$scalingActive = width != targetWidth || height != targetHeight;
        if (this.modernopt$scalingActive) {
            RenderScalePhase.begin(width, height, targetWidth, targetHeight);
            // Mark before resizing: a failed resize may have changed some targets
            // before throwing, so the native-size rollback must still be attempted.
            this.modernopt$auxTargetsAdjusted = true;
            if (!RenderTargetLifecycle.syncWorldTargets(this.minecraft, width, height)) {
                RenderScalePhase.end();
                modernopt$restoreAuxiliaryTargets(targetWidth, targetHeight);
                width = targetWidth;
                height = targetHeight;
                scalingRequested = false;
                ssaaRequested = false;
                samples = 0;
                this.modernopt$scalingActive = false;
            }
        } else {
            modernopt$restoreAuxiliaryTargets(targetWidth, targetHeight);
        }
        this.modernopt$ssaaActive = ssaaRequested;

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
                        requestedSamples = 8;
                        ModernOptConfig.MSAA_SAMPLES.set(8);
                        ModernOptConfig.SPEC.save();
                        this.modernopt$msaaTarget = new MultisampleRenderTarget(width, height, samples, Minecraft.ON_OSX);
                    }
                    // Track the requested mode, not the fallback target, to avoid retrying 16x every frame.
                    this.modernopt$allocatedSamples = requestedSamples;
                } else if (this.modernopt$msaaTarget.width != width || this.modernopt$msaaTarget.height != height) {
                    this.modernopt$msaaTarget.resize(width, height, Minecraft.ON_OSX);
                }
                this.modernopt$failedMsaaRequest = 0;
                this.modernopt$worldTarget = this.modernopt$msaaTarget;
                this.modernopt$msaaActive = true;
            } catch (RuntimeException | LinkageError msaaUnavailable) {
                // Unsupported sample counts/framebuffer formats must not break world rendering.
                if (this.modernopt$msaaTarget != null) this.modernopt$msaaTarget.destroyBuffers();
                this.modernopt$msaaTarget = null;
                this.modernopt$allocatedSamples = 0;
                this.modernopt$failedMsaaRequest = requestedSamples;
                this.modernopt$msaaActive = false;
                this.modernopt$worldTarget = null;
            }
        }

        if (this.modernopt$worldTarget == null && this.modernopt$scalingActive) {
            try {
                if (this.modernopt$scaledTarget == null) {
                    this.modernopt$scaledTarget = new TextureTarget(width, height, true, Minecraft.ON_OSX);
                } else if (this.modernopt$scaledTarget.width != width || this.modernopt$scaledTarget.height != height) {
                    this.modernopt$scaledTarget.resize(width, height, Minecraft.ON_OSX);
                }
                this.modernopt$worldTarget = this.modernopt$scaledTarget;
            } catch (RuntimeException | LinkageError allocationFailure) {
                MODERNOPT_LOGGER.warn("Could not allocate the scaled world target; using the native framebuffer for this frame.", allocationFailure);
                RenderScalePhase.end();
                modernopt$restoreAuxiliaryTargets(targetWidth, targetHeight);
                this.modernopt$scalingActive = false;
                this.modernopt$ssaaActive = false;
                this.modernopt$originalTarget = fxaaRequested ? original : null;
                this.modernopt$fxaaOnly = fxaaRequested;
                return;
            }
        }

        if (this.modernopt$worldTarget == null) {
            RenderScalePhase.end();
            modernopt$restoreAuxiliaryTargets(targetWidth, targetHeight);
            this.modernopt$originalTarget = fxaaRequested ? original : null;
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

    /**
     * Shader-free framebuffer copy/scale. This is the lowest-overhead path for
     * the "None" upscaler and also resolves MSAA directly into the native target,
     * avoiding an intermediate texture resolve followed by a textured draw.
     */
    @Unique
    private boolean modernopt$blitFramebuffer(RenderTarget input, RenderTarget output) {
        if (input == null || output == null || input.width <= 0 || input.height <= 0
            || output.width <= 0 || output.height <= 0) return false;

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
            // Do not call glGetError here: it can force driver-side synchronization,
            // and it also consumes any pre-existing GL error from another mod.
            // Both framebuffer targets are ModernOPT/Minecraft-owned compatible targets.
            return true;
        } catch (RuntimeException | LinkageError exception) {
            MODERNOPT_LOGGER.warn("Direct framebuffer copy failed; falling back to the textured blit.", exception);
            return false;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            output.bindWrite(true);
            RenderSystem.viewport(0, 0, output.width, output.height);
        }
    }

    @Unique
    private void modernopt$blitOpaque(RenderTarget input, RenderTarget output) {
        int width = output.width;
        int height = output.height;
        output.bindWrite(true);
        RenderSystem.viewport(0, 0, width, height);
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, input.getColorTextureId());
        BufferBuilder builder = Tesselator.getInstance().begin(
            VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX
        );
        // Framebuffer textures are bottom-left-origin; the destination quad is top-left-origin.
        builder.addVertex(0.0F, 0.0F, 0.0F).setUv(0.0F, 1.0F);
        builder.addVertex(0.0F, height, 0.0F).setUv(0.0F, 0.0F);
        builder.addVertex(width, height, 0.0F).setUv(1.0F, 0.0F);
        builder.addVertex(width, 0.0F, 0.0F).setUv(1.0F, 1.0F);
        BufferUploader.drawWithShader(builder.build());
    }

    @Unique
    private void modernopt$restoreAuxiliaryTargets(int width, int height) {
        if (!this.modernopt$auxTargetsAdjusted) return;
        if (RenderTargetLifecycle.syncWorldTargets(this.minecraft, width, height)) {
            this.modernopt$auxTargetsAdjusted = false;
        }
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void modernopt$endWorldPass(CallbackInfo ci) {
        RenderScalePhase.end();
        if (!this.modernopt$active && !this.modernopt$fxaaOnly) {
            modernopt$finishDiagnostics();
            return;
        }

        RenderDiagnostics.beginPostProcess();
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
                boolean upscaled;
                if (this.modernopt$ssaaActive) {
                    upscaled = Fsr1Renderer.downsampleSsaa(this.modernopt$worldTarget, this.modernopt$originalTarget);
                } else if (ModernOptConfig.UPSCALER_MODE.get() == 0
                    || ((ModernOptConfig.UPSCALER_MODE.get() == 2 || ModernOptConfig.UPSCALER_MODE.get() == 3)
                        && this.modernopt$worldTarget.width == width
                        && this.modernopt$worldTarget.height == height)) {
                    // Bilinear and nearest-neighbor are identity operations at equal dimensions.
                    // Use a fixed-function framebuffer blit instead of running a redundant shader pass.
                    upscaled = modernopt$blitFramebuffer(this.modernopt$worldTarget, this.modernopt$originalTarget);
                } else {
                    upscaled = Fsr1Renderer.render(this.modernopt$worldTarget, this.modernopt$originalTarget);
                }
                if (!upscaled) {
                    // A failed optimized pass restores the image using the safe textured fallback.
                    modernopt$blitOpaque(this.modernopt$worldTarget, this.modernopt$originalTarget);
                }
            } else if (this.modernopt$active && this.modernopt$msaaActive) {
                // Resolve MSAA directly into the native framebuffer without an intermediate texture draw.
                if (!modernopt$blitFramebuffer(this.modernopt$worldTarget, this.modernopt$originalTarget)) {
                    modernopt$blitOpaque(this.modernopt$worldTarget, this.modernopt$originalTarget);
                }
            }

            if (!this.modernopt$shaderPackInUseThisFrame && ModernOptConfig.FXAA_ENABLED.get()) {
                Fsr1Renderer.applyFxaa(this.modernopt$originalTarget);
            }
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            if (this.modernopt$oldProjection != null) {
                RenderSystem.setProjectionMatrix(this.modernopt$oldProjection, this.modernopt$oldVertexSorting);
                this.modernopt$oldProjection = null;
                this.modernopt$oldVertexSorting = null;
            }
            ((MinecraftRenderTargetMixin)(Object)this.minecraft).modernopt$setMainRenderTarget(this.modernopt$originalTarget);
            this.modernopt$originalTarget.bindWrite(true);
            modernopt$finishDiagnostics();
            this.modernopt$active = false;
            this.modernopt$scalingActive = false;
            this.modernopt$msaaActive = false;
            this.modernopt$ssaaActive = false;
            this.modernopt$fxaaOnly = false;
            this.modernopt$originalTarget = null;
            this.modernopt$worldTarget = null;
        }
    }
    @Unique
    private void modernopt$finishDiagnostics() {
        RenderTarget nativeTarget = this.minecraft.getMainRenderTarget();
        int nativeWidth = nativeTarget == null ? 0 : nativeTarget.width;
        int nativeHeight = nativeTarget == null ? 0 : nativeTarget.height;
        RenderTarget worldTarget = this.modernopt$worldTarget;
        int renderWidth = worldTarget == null ? nativeWidth : worldTarget.width;
        int renderHeight = worldTarget == null ? nativeHeight : worldTarget.height;
        RenderDiagnostics.endFrame(nativeWidth, nativeHeight, renderWidth, renderHeight,
            ModernOptConfig.UPSCALER_MODE.get(), ModernOptConfig.ENABLED.get(),
            this.modernopt$shaderPackInUseThisFrame);
    }

}
