package net.cr512.modernopt.mixin.iris;

import net.cr512.modernopt.render.GpuProfiler;
import net.cr512.modernopt.render.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times Iris' shadow pass (shadow map geometry plus the pack's shadow composites) and makes sure
 * the shadow map framebuffer is the one bound while it runs.
 *
 * <p>Target is resolved by name so ModernOPT keeps compiling without an Iris dependency; the
 * whole config is skipped when Iris is absent (see {@link ModernOptIrisMixinPlugin}).</p>
 *
 * <p>Why the explicit rebind: Iris binds its shadow framebuffer once in
 * {@code IrisRenderingPipeline#beginLevelRendering}. ModernOPT renders the world into its scaled
 * target, and the "main render target" redirect plus Sodium's shader reset can leave the scaled
 * colour target bound when the shadow pass starts. The shadow pass would then draw light-space
 * geometry into the visible image with a 2048x2048 viewport. Binding the shadow framebuffer again
 * here is exactly the state Iris expects, so it is safe even when nothing clobbered it.</p>
 */
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
public abstract class IrisShadowRendererMixin {

    private static java.lang.reflect.Field modernopt$targetsField;
    private static java.lang.reflect.Method modernopt$getDepthSourceFb;
    private static java.lang.reflect.Method modernopt$bind;

    private static java.lang.reflect.Method modernopt$findMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                java.lang.reflect.Method method = current.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            }
        }
        return null;
    }

    /** Binds Iris' shadow depth-source framebuffer; returns its GL id, or -1 on failure. */
    private static int modernopt$bindShadowFramebuffer(Object shadowRenderer) {
        try {
            if (modernopt$targetsField == null) {
                modernopt$targetsField = shadowRenderer.getClass().getDeclaredField("targets");
                modernopt$targetsField.setAccessible(true);
            }
            Object targets = modernopt$targetsField.get(shadowRenderer);
            if (targets == null) {
                return -1;
            }
            if (modernopt$getDepthSourceFb == null) {
                modernopt$getDepthSourceFb = modernopt$findMethod(targets.getClass(), "getDepthSourceFb");
                Class<?> framebufferClass = modernopt$getDepthSourceFb.getReturnType();
                modernopt$bind = modernopt$findMethod(framebufferClass, "bind");
            }
            Object framebuffer = modernopt$getDepthSourceFb.invoke(targets);
            if (framebuffer == null) {
                return -1;
            }
            modernopt$bind.invoke(framebuffer);
            return 0;
        } catch (Throwable throwable) {
            net.cr512.modernopt.ModernOPT.LOG.warn(
                    "[ModernOPT] could not bind Iris' shadow framebuffer", throwable);
            return -1;
        }
    }

    @Inject(method = "renderShadows", at = @At("HEAD"), require = 0, remap = false)
    private void modernopt$beginShadowPass(CallbackInfo callbackInfo) {
        RenderPipeline.setShadowPassActive(true);
        GpuProfiler.begin(GpuProfiler.SHADOW);
        modernopt$bindShadowFramebuffer(this);
    }

    @Inject(method = "renderShadows", at = @At("RETURN"), require = 0, remap = false)
    private void modernopt$endShadowPass(CallbackInfo callbackInfo) {
        RenderPipeline.setShadowPassActive(false);
        GpuProfiler.end(GpuProfiler.SHADOW);
    }
}
