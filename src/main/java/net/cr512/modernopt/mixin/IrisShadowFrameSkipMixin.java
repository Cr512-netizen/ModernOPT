package net.cr512.modernopt.mixin;

import net.cr512.modernopt.config.ModernOptConfig;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Optional Vira-style shadow refresh throttling for Iris 1.21.1.
 *
 * Hook the whole IrisRenderingPipeline.renderShadows call, not only
 * ShadowRenderer.renderShadows: the pipeline method also runs Iris' PREPARE
 * pass after shadow rendering. Cancelling only ShadowRenderer can still let
 * PREPARE clear or mutate the cached shadow targets.
 *
 * A skipped frame retains both the last shadow map and the matrices from the
 * frame that produced it. Iris is optional, so reflection is used only to
 * verify that the pipeline really has an active shadow renderer. If the
 * expected fields/API are unavailable, this integration fails open.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisShadowFrameSkipMixin {
    @Unique private static volatile boolean modernopt$irisReflectionResolved;
    @Unique private static Field modernopt$shadowRendererField;
    @Unique private static Field modernopt$shadowDistanceField;
    @Unique private static Method modernopt$getOverriddenShadowDistance;

    @Unique private long modernopt$lastShadowUpdateNanos;
    @Unique private boolean modernopt$shadowUpdatePending;

    @Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void modernopt$throttleShadowPipeline(CallbackInfo ci) {
        this.modernopt$shadowUpdatePending = false;

        if (!ModernOptConfig.ENABLED.get() || !ModernOptConfig.SHADOW_FRAME_SKIP_ENABLED.get()
            || !ModList.get().isLoaded("iris")) {
            this.modernopt$lastShadowUpdateNanos = 0L;
            return;
        }

        // Do not skip PREPARE for packs without active shadows or when Iris
        // has disabled shadows through its own settings/directives.
        if (!modernopt$hasActiveShadowRenderer(this)) {
            this.modernopt$lastShadowUpdateNanos = 0L;
            return;
        }

        int targetFps = Math.max(40, Math.min(240, ModernOptConfig.SHADOW_UPDATE_FPS.get()));
        long minimumIntervalNanos = 1_000_000_000L / targetFps;
        long now = System.nanoTime();

        // The first pass always runs. Below the selected refresh rate, every
        // rendered frame updates shadows; above it, the previous map is held.
        if (this.modernopt$lastShadowUpdateNanos != 0L
            && now - this.modernopt$lastShadowUpdateNanos < minimumIntervalNanos) {
            ci.cancel();
            return;
        }

        this.modernopt$shadowUpdatePending = true;
    }

    @Inject(method = "renderShadows", at = @At("RETURN"), remap = false, require = 0)
    private void modernopt$rememberSuccessfulShadowUpdate(CallbackInfo ci) {
        if (this.modernopt$shadowUpdatePending) {
            this.modernopt$lastShadowUpdateNanos = System.nanoTime();
            this.modernopt$shadowUpdatePending = false;
        }
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
            // Compatibility is more important than skipping a shadow pass.
            return false;
        }
    }

    @Unique
    private static synchronized boolean modernopt$resolveIrisReflection() {
        if (modernopt$irisReflectionResolved) {
            return modernopt$shadowRendererField != null
                && modernopt$shadowDistanceField != null
                && modernopt$getOverriddenShadowDistance != null;
        }

        modernopt$irisReflectionResolved = true;
        try {
            ClassLoader loader = IrisShadowFrameSkipMixin.class.getClassLoader();
            Class<?> pipelineClass = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline", false, loader);
            Class<?> settingsClass = Class.forName("net.irisshaders.iris.gui.option.IrisVideoSettings", false, loader);

            modernopt$shadowRendererField = pipelineClass.getDeclaredField("shadowRenderer");
            modernopt$shadowRendererField.setAccessible(true);
            modernopt$shadowDistanceField = settingsClass.getDeclaredField("shadowDistance");
            modernopt$shadowDistanceField.setAccessible(true);
            modernopt$getOverriddenShadowDistance = settingsClass.getDeclaredMethod("getOverriddenShadowDistance", int.class);
            modernopt$getOverriddenShadowDistance.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            modernopt$shadowRendererField = null;
            modernopt$shadowDistanceField = null;
            modernopt$getOverriddenShadowDistance = null;
        }

        return modernopt$shadowRendererField != null
            && modernopt$shadowDistanceField != null
            && modernopt$getOverriddenShadowDistance != null;
    }
}
