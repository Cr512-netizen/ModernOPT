package net.cr512.modernopt.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.render.Fsr1Renderer;
import net.cr512.modernopt.render.RenderDiagnostics;
import net.cr512.modernopt.render.RenderScaleController;
import net.cr512.modernopt.render.RenderPipelinePolicy;
import net.cr512.modernopt.render.RenderTargetManager;
import net.cr512.modernopt.render.FramebufferBlitter;
import net.cr512.modernopt.render.RenderScalePhase;
import net.cr512.modernopt.render.RenderTargetLifecycle;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Unique private static final Logger MODERNOPT_LOGGER = LoggerFactory.getLogger("ModernOPT");

    @Shadow @Final private Minecraft minecraft;

    @Unique private RenderTarget modernopt$originalTarget;
    @Unique private RenderTarget modernopt$frameNativeTarget;
    @Unique private final RenderTargetManager modernopt$targetManager = new RenderTargetManager();
    @Unique private RenderTarget modernopt$worldTarget;
    @Unique private Matrix4f modernopt$oldProjection;
    @Unique private VertexSorting modernopt$oldVertexSorting;
    @Unique private boolean modernopt$active;
    @Unique private boolean modernopt$shaderPackInUseThisFrame;
    @Unique private boolean modernopt$scalingActive;
    @Unique private boolean modernopt$msaaActive;
    @Unique private boolean modernopt$ssaaActive;
    @Unique private boolean modernopt$fxaaOnly;
    @Unique private boolean modernopt$fxaaRequested;
    @Unique private boolean modernopt$auxTargetsAdjusted;
    @Unique private boolean modernopt$hasCameraSample;
    @Unique private double modernopt$lastCameraX;
    @Unique private double modernopt$lastCameraY;
    @Unique private double modernopt$lastCameraZ;
    @Unique private float modernopt$lastCameraYaw;
    @Unique private float modernopt$lastCameraPitch;

    // Iris is optional, so avoid a hard dependency. Resolve its API once instead of
    // repeating class/method lookup on every rendered frame.
    @Unique private static java.lang.reflect.Method modernopt$irisShaderPackMethod;
    @Unique private static Object modernopt$irisApiInstance;
    @Unique private static boolean modernopt$irisApiResolved;

    @Unique
    private static boolean modernopt$shaderPackInUse() {
        if (!net.neoforged.fml.ModList.get().isLoaded("iris")) return false;
        if (!modernopt$irisApiResolved) {
            // Set the latch first: a broken/incompatible Iris API must not trigger
            // repeated reflective discovery on every frame.
            modernopt$irisApiResolved = true;
            try {
                Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                modernopt$irisApiInstance = apiClass.getMethod("getInstance").invoke(null);
                modernopt$irisShaderPackMethod = apiClass.getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException | LinkageError ignored) {
                modernopt$irisApiInstance = null;
                modernopt$irisShaderPackMethod = null;
                // Fail closed: if Iris is present but its API cannot be queried,
                // skip ModernOPT AA to avoid double-processing an unknown shader path.
                return true;
            }
        }
        if (modernopt$irisShaderPackMethod == null || modernopt$irisApiInstance == null) return true;
        try {
            return Boolean.TRUE.equals(modernopt$irisShaderPackMethod.invoke(modernopt$irisApiInstance));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Preserve shader-pack safety if Iris changes or throws at runtime.
            return true;
        }
    }

    @Unique
    private static int modernopt$requestedSamples() {
        return switch (ModernOptConfig.MSAA_SAMPLES.get()) {
            case 2 -> 2;
            case 4 -> 4;
            case 8 -> 8;
            default -> 0;
        };
    }

    @Inject(method = "close", at = @At("HEAD"), require = 0)
    private void modernopt$releaseOwnedRenderTargets(CallbackInfo ci) {
        this.modernopt$targetManager.destroy();
        Fsr1Renderer.releaseResources();
        RenderDiagnostics.releaseGpuQueries();
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
        this.modernopt$fxaaRequested = false;
        this.modernopt$worldTarget = null;
        RenderTarget nativeTarget = this.minecraft.getMainRenderTarget();
        this.modernopt$frameNativeTarget = nativeTarget;
        int nativeWidth = nativeTarget.width;
        int nativeHeight = nativeTarget.height;
        if (!ModernOptConfig.ENABLED.get() || this.minecraft.level == null) {
            this.modernopt$hasCameraSample = false;
            RenderScaleController.update(false, false, false);
            modernopt$restoreAuxiliaryTargets(nativeWidth, nativeHeight);
            return;
        }
        // Shader packs provide their own temporal/spatial AA. Do not run ModernOPT
        // AA over them, but keep the spatial render-scale/upscaler path available.
        // Iris' target integration remains best-effort; if target synchronization fails,
        // the existing rollback below returns to native rendering for that frame.
        this.modernopt$shaderPackInUseThisFrame = modernopt$shaderPackInUse();
        boolean shaderPackInUse = this.modernopt$shaderPackInUseThisFrame;
        boolean cameraMoving = modernopt$cameraMoving();
        RenderScaleController.update(shaderPackInUse, true, cameraMoving);

        RenderPipelinePolicy.Selection selection = RenderPipelinePolicy.select(
            shaderPackInUse,
            RenderScaleController.isEnabled(),
            ModernOptConfig.SSAA_ENABLED.get(),
            ModernOptConfig.FXAA_ENABLED.get(),
            modernopt$requestedSamples(),
            this.modernopt$targetManager.isMsaaRequestFailed(modernopt$requestedSamples()) ? modernopt$requestedSamples() : 0
        );
        boolean ssaaRequested = selection.ssaa();
        boolean fxaaRequested = selection.fxaa();
        this.modernopt$fxaaRequested = fxaaRequested;
        boolean scalingRequested = selection.scaling();
        int samples = selection.msaaSamples();
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
                this.modernopt$worldTarget = this.modernopt$targetManager.acquireMsaaTarget(
                    width, height, samples, Minecraft.ON_OSX
                );
                this.modernopt$msaaActive = true;
            } catch (RuntimeException | LinkageError msaaUnavailable) {
                // Unsupported sample counts/framebuffer formats must not break world rendering.
                this.modernopt$targetManager.markMsaaFailure(requestedSamples);
                this.modernopt$msaaActive = false;
                this.modernopt$worldTarget = null;
            }
        }

        if (this.modernopt$worldTarget == null && this.modernopt$scalingActive) {
            try {
                this.modernopt$worldTarget = this.modernopt$targetManager.acquireScaledTarget(width, height, Minecraft.ON_OSX);
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

    @Unique
    private boolean modernopt$cameraMoving() {
        if (this.minecraft.player == null) {
            this.modernopt$hasCameraSample = false;
            return false;
        }
        var camera = this.minecraft.gameRenderer.getMainCamera();
        var position = camera.getPosition();
        double x = position.x, y = position.y, z = position.z;
        float yaw = camera.getYRot(), pitch = camera.getXRot();
        if (!this.modernopt$hasCameraSample) {
            this.modernopt$lastCameraX = x;
            this.modernopt$lastCameraY = y;
            this.modernopt$lastCameraZ = z;
            this.modernopt$lastCameraYaw = yaw;
            this.modernopt$lastCameraPitch = pitch;
            this.modernopt$hasCameraSample = true;
            return false;
        }
        double dx = x - this.modernopt$lastCameraX;
        double dy = y - this.modernopt$lastCameraY;
        double dz = z - this.modernopt$lastCameraZ;
        boolean moving = dx * dx + dy * dy + dz * dz > 1.0e-6
            || modernopt$angleDelta(yaw, this.modernopt$lastCameraYaw) > 0.05
            || modernopt$angleDelta(pitch, this.modernopt$lastCameraPitch) > 0.05;
        this.modernopt$lastCameraX = x;
        this.modernopt$lastCameraY = y;
        this.modernopt$lastCameraZ = z;
        this.modernopt$lastCameraYaw = yaw;
        this.modernopt$lastCameraPitch = pitch;
        return moving;
    }

    @Unique
    private static double modernopt$angleDelta(float current, float previous) {
        double delta = (current - previous) % 360.0;
        if (delta > 180.0) delta -= 360.0;
        if (delta < -180.0) delta += 360.0;
        return Math.abs(delta);
    }

    /**
     * Shader-free framebuffer copy/scale. This is the lowest-overhead path for
     * the "None" upscaler and also resolves MSAA directly into the native target,
     * avoiding an intermediate texture resolve followed by a textured draw.
     */
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
            net.cr512.modernopt.render.WorldPostProcessStage.execute(
                this.minecraft,
                this.modernopt$originalTarget,
                this.modernopt$worldTarget,
                this.modernopt$active,
                this.modernopt$scalingActive,
                this.modernopt$ssaaActive,
                this.modernopt$msaaActive,
                this.modernopt$fxaaRequested,
                this.modernopt$shaderPackInUseThisFrame
            );
        } finally {
            modernopt$finishDiagnostics();
            this.modernopt$active = false;
            this.modernopt$scalingActive = false;
            this.modernopt$msaaActive = false;
            this.modernopt$ssaaActive = false;
            this.modernopt$fxaaOnly = false;
            this.modernopt$fxaaRequested = false;
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
        boolean targetRestored = nativeTarget != null && nativeTarget == this.modernopt$frameNativeTarget;
        boolean captureDetails = ModernOptConfig.LOGGING_ENABLED.get()
            || ModernOptConfig.PROFILING_ENABLED.get()
            || (ModernOptConfig.OVERLAY_ENABLED.get() && ModernOptConfig.OVERLAY_SHOW_RENDER_INFO.get())
            || net.cr512.modernopt.client.BenchmarkRecorder.isRecording();
        String upscalerPath = captureDetails
            ? (ModernOptConfig.ENABLED.get() ? Fsr1Renderer.diagnosticStatus() : "disabled; vanilla path")
            : "not sampled";
        String auxiliaryTargets = captureDetails
            ? RenderTargetLifecycle.diagnosticSummary(this.minecraft)
            : "not sampled";
        RenderDiagnostics.recordFrameState(targetRestored, upscalerPath, auxiliaryTargets);
        RenderDiagnostics.endFrame(nativeWidth, nativeHeight, renderWidth, renderHeight,
            ModernOptConfig.UPSCALER_MODE.get(), ModernOptConfig.ENABLED.get(),
            this.modernopt$shaderPackInUseThisFrame);
        this.modernopt$frameNativeTarget = null;
    }

}
