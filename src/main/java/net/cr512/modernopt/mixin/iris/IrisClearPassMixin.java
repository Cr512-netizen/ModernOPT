package net.cr512.modernopt.mixin.iris;

import net.cr512.modernopt.config.ModernOptSettings;
import net.cr512.modernopt.render.RenderPipeline;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the held shadow map from being cleared.
 *
 * <p>The pipeline clears its shadow targets once per frame before the shadow pass. When the map is
 * reused for several frames that clear would wipe exactly what is being reused, so clears that
 * target the shadow framebuffer are skipped on the frames that do not redraw it. Everything else
 * (main colour targets and their clear passes) is untouched.</p>
 */
@Mixin(targets = "net.irisshaders.iris.targets.ClearPass", remap = false)
public abstract class IrisClearPassMixin {

    private static java.lang.reflect.Method modernopt$getFramebuffer;
    private static java.lang.reflect.Method modernopt$getGlId;

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void modernopt$holdShadowMap(Vector4f color, CallbackInfo callbackInfo) {
        int shadowFramebuffer = RenderPipeline.shadowHoldFramebuffer();
        if (shadowFramebuffer <= 0) {
            return;
        }
        int interval = Math.max(1, ModernOptSettings.shadowUpdateInterval());
        if (RenderPipeline.shadowHoldShouldDraw(interval)) {
            return;
        }
        try {
            if (modernopt$getFramebuffer == null) {
                modernopt$getFramebuffer = modernopt$findMethod(this.getClass(), "getFramebuffer");
            }
            Object framebuffer = modernopt$getFramebuffer.invoke(this);
            if (framebuffer == null) {
                return;
            }
            if (modernopt$getGlId == null) {
                modernopt$getGlId = modernopt$findMethod(framebuffer.getClass(), "getGlId");
            }
            Object id = modernopt$getGlId.invoke(framebuffer);
            if (id instanceof Integer && (Integer) id == shadowFramebuffer) {
                callbackInfo.cancel();
            }
        } catch (Throwable ignored) {
            // Never let a diagnostic path break the frame.
        }
    }

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
}
