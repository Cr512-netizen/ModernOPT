package net.cr512.modernopt.mixin;

import net.cr512.modernopt.config.ModernOptConfig;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Iris shadow refresh pacing. When a refresh is skipped, suppress only the
 * shadow target clear/compute work in beginLevelRendering and cancel only the
 * ShadowRenderer draw. Iris' pipeline method still runs its non-shadow PREPARE
 * renderer, while ShadowRenderer.MODELVIEW/PROJECTION and the previous shadow
 * maps remain paired.
 *
 * This is intentionally fail-open: if the Iris version's expected fields or
 * target lifecycle cannot be verified, shadows render every frame.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisShadowFrameSkipMixin {
    @Unique private static volatile boolean modernopt$irisReflectionResolved;
    @Unique private static Field modernopt$shadowRendererField;
    @Unique private static Field modernopt$shadowDistanceField;
    @Unique private static Method modernopt$getOverriddenShadowDistance;
    @Unique private static Field modernopt$shadowTargetsField;
    @Unique private static Method modernopt$isFullClearRequired;

    @Unique private boolean modernopt$shadowUpdatePending;
    @Unique private long modernopt$lastShadowUpdateNanos;

    @Inject(method = "beginLevelRendering", at = @At("HEAD"), remap = false, require = 0)
    private void modernopt$selectShadowRefresh(CallbackInfo ci) {
        net.cr512.modernopt.render.IrisShadowFrameSkipState.skipShadowPass = false;
        net.cr512.modernopt.render.IrisShadowFrameSkipState.insideShadowSetup = false;
        this.modernopt$shadowUpdatePending = false;

        if (!ModernOptConfig.ENABLED.get() || !ModernOptConfig.SHADOW_FRAME_SKIP_ENABLED.get()
            || !ModList.get().isLoaded("iris")) {
            this.modernopt$lastShadowUpdateNanos = 0L;
            return;
        }

        if (!modernopt$hasActiveShadowRenderer(this)) {
            this.modernopt$lastShadowUpdateNanos = 0L;
            return;
        }

        // A target resize/reload requires a full clear; never hold stale contents across it.
        if (modernopt$fullShadowClearRequired(this)) {
            this.modernopt$lastShadowUpdateNanos = 0L;
            return;
        }

        int targetFps = Math.max(40, Math.min(240, ModernOptConfig.SHADOW_UPDATE_FPS.get()));
        long minimumIntervalNanos = 1_000_000_000L / targetFps;
        long now = System.nanoTime();

        if (this.modernopt$lastShadowUpdateNanos != 0L
            && now - this.modernopt$lastShadowUpdateNanos < minimumIntervalNanos) {
            net.cr512.modernopt.render.IrisShadowFrameSkipState.skipShadowPass = true;
            net.cr512.modernopt.render.IrisShadowFrameSkipState.insideShadowSetup = true;
        } else {
            this.modernopt$shadowUpdatePending = true;
        }
    }

    @Inject(method = "beginLevelRendering", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/uniforms/FrameUpdateNotifier;onNewFrame()V", shift = At.Shift.BEFORE),
        remap = false, require = 0)
    private void modernopt$endShadowSetupWindow(CallbackInfo ci) {
        net.cr512.modernopt.render.IrisShadowFrameSkipState.insideShadowSetup = false;
    }

    @Redirect(method = "beginLevelRendering", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"), remap = false, require = 0)
    private void modernopt$preserveCachedShadowDepth(int mask, boolean onOSX) {
        if (!net.cr512.modernopt.render.IrisShadowFrameSkipState.skipShadowPass) {
            com.mojang.blaze3d.systems.RenderSystem.clear(mask, onOSX);
        }
        // The only clear(mask, onOSX) in this section clears Iris' shadow depth target.
    }

    @Inject(method = "renderShadows", at = @At("RETURN"), remap = false, require = 0)
    private void modernopt$finishShadowRefresh(CallbackInfo ci) {
        if (this.modernopt$shadowUpdatePending) {
            this.modernopt$lastShadowUpdateNanos = System.nanoTime();
        }
        this.modernopt$shadowUpdatePending = false;
        net.cr512.modernopt.render.IrisShadowFrameSkipState.insideShadowSetup = false;
        net.cr512.modernopt.render.IrisShadowFrameSkipState.skipShadowPass = false;
    }

    @Unique
    private static boolean modernopt$hasActiveShadowRenderer(Object pipeline) {
        if (!modernopt$resolveIrisReflection()) return false;
        try {
            if (modernopt$shadowRendererField.get(pipeline) == null) return false;
            int configuredDistance = modernopt$shadowDistanceField.getInt(null);
            Object effectiveDistance = modernopt$getOverriddenShadowDistance.invoke(null, configuredDistance);
            return effectiveDistance instanceof Integer distance && distance > 0;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @Unique
    private static boolean modernopt$fullShadowClearRequired(Object pipeline) {
        if (!modernopt$resolveIrisReflection()) return true;
        try {
            Object targets = modernopt$shadowTargetsField.get(pipeline);
            if (targets == null) return true;
            return Boolean.TRUE.equals(modernopt$isFullClearRequired.invoke(targets));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return true;
        }
    }

    @Unique
    private static synchronized boolean modernopt$resolveIrisReflection() {
        if (modernopt$irisReflectionResolved) {
            return modernopt$shadowRendererField != null && modernopt$shadowDistanceField != null
                && modernopt$getOverriddenShadowDistance != null && modernopt$shadowTargetsField != null
                && modernopt$isFullClearRequired != null;
        }

        modernopt$irisReflectionResolved = true;
        try {
            ClassLoader loader = IrisShadowFrameSkipMixin.class.getClassLoader();
            Class<?> pipelineClass = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline", false, loader);
            Class<?> settingsClass = Class.forName("net.irisshaders.iris.gui.option.IrisVideoSettings", false, loader);
            Class<?> targetsClass = Class.forName("net.irisshaders.iris.shadows.ShadowRenderTargets", false, loader);

            modernopt$shadowRendererField = pipelineClass.getDeclaredField("shadowRenderer");
            modernopt$shadowRendererField.setAccessible(true);
            modernopt$shadowTargetsField = pipelineClass.getDeclaredField("shadowRenderTargets");
            modernopt$shadowTargetsField.setAccessible(true);
            modernopt$shadowDistanceField = settingsClass.getDeclaredField("shadowDistance");
            modernopt$shadowDistanceField.setAccessible(true);
            modernopt$getOverriddenShadowDistance = settingsClass.getDeclaredMethod("getOverriddenShadowDistance", int.class);
            modernopt$getOverriddenShadowDistance.setAccessible(true);
            modernopt$isFullClearRequired = targetsClass.getDeclaredMethod("isFullClearRequired");
            modernopt$isFullClearRequired.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            modernopt$shadowRendererField = null;
            modernopt$shadowTargetsField = null;
            modernopt$shadowDistanceField = null;
            modernopt$getOverriddenShadowDistance = null;
            modernopt$isFullClearRequired = null;
        }

        return modernopt$shadowRendererField != null && modernopt$shadowDistanceField != null
            && modernopt$getOverriddenShadowDistance != null && modernopt$shadowTargetsField != null
            && modernopt$isFullClearRequired != null;
    }
}
