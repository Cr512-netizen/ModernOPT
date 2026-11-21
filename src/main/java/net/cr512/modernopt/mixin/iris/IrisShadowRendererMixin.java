// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.mixin.iris;

import net.cr512.modernopt.ModernOPT;
import net.cr512.modernopt.compat.ShadowClearRegistry;
import net.cr512.modernopt.config.ModernOptSettings;
import net.cr512.modernopt.render.GpuProfiler;
import net.cr512.modernopt.render.RenderPipeline;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times the shadow pass, keeps the shadow framebuffer bound, and optionally holds the shadow map
 * for several frames.
 *
 * <p>Every frame a pack draws the whole world a second time from the sun into a large depth map.
 * With {@code shadows.updateInterval > 1} that pass is skipped for the following frames and the
 * map keeps being sampled instead, which removes its full cost from those frames. To keep the
 * result stable the matrices the pack uses for shadow lookups are restored from the frame that
 * actually drew the map and translated by how far the camera has moved since, so the shadows stay
 * attached to the world instead of swimming.</p>
 *
 * <p>The implementation is written from the documented render-scale behaviour and only uses the
 * public matrix fields the shadow pass itself publishes. It reuses no code from other mods.
 * Everything is neutralised with interval 1, and the hold is disabled with one warning when the
 * clear-pass hooks did not run.</p>
 */
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
public abstract class IrisShadowRendererMixin {

    private static final Matrix4f modernopt$heldModelView = new Matrix4f();
    private static final Matrix4f modernopt$heldProjection = new Matrix4f();
    private static double modernopt$heldX;
    private static double modernopt$heldY;
    private static double modernopt$heldZ;

    private static java.lang.reflect.Field modernopt$modelViewField;
    private static java.lang.reflect.Field modernopt$projectionField;
    private static java.lang.reflect.Field modernopt$targetsField;
    private static java.lang.reflect.Field modernopt$halfPlaneField;
    private static java.lang.reflect.Field modernopt$renderMulField;
    private static java.lang.reflect.Field modernopt$entityMulField;
    private static java.lang.reflect.Method modernopt$getDepthSourceFb;
    private static java.lang.reflect.Method modernopt$bind;
    private static java.lang.reflect.Method modernopt$getGlId;
    private static long modernopt$lastLog;

    @Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void modernopt$beginShadowPass(CallbackInfo callbackInfo) {
        ShadowClearRegistry.verifyAfterPipelineLoad();
        int interval = Math.max(1, ModernOptSettings.shadowUpdateInterval());
        boolean holdActive = interval > 1 && !ShadowClearRegistry.holdDisabled();
        if (!holdActive) {
            // Every-frame shadows are plain Iris behaviour: no hold and no matrix swizzling, but
            // still count the drawn frame and time the pass for the overlay/profiler.
            RenderPipeline.setShadowPassActive(true);
            RenderPipeline.shadowHoldNoteDrawn();
            GpuProfiler.begin(GpuProfiler.SHADOW);
            return;
        }
        if (!RenderPipeline.shadowHoldShouldDraw(interval)) {
            RenderPipeline.shadowHoldNoteSkipped();
            modernopt$holdMatricesForThisCamera();
            callbackInfo.cancel();
            return;
        }

        RenderPipeline.setShadowPassActive(true);
        GpuProfiler.begin(GpuProfiler.SHADOW);
        int shadowFbo = modernopt$bindShadowFramebuffer(this);
        if (Boolean.getBoolean("modernopt.bench")) {
            long now = System.nanoTime();
            if (now - modernopt$lastLog > 1_000_000_000L) {
                modernopt$lastLog = now;
                int[] viewport = new int[4];
                org.lwjgl.opengl.GL11C.glGetIntegerv(org.lwjgl.opengl.GL11C.GL_VIEWPORT, viewport);
                ModernOPT.LOG.info("[BENCH][SHADOWGL] begin viewport={}x{} shadowFbo={} interval={}",
                        viewport[2], viewport[3], shadowFbo, interval);
            }
        }
    }

    @Inject(method = "renderShadows", at = @At("RETURN"), require = 0, remap = false)
    private void modernopt$endShadowPass(CallbackInfo callbackInfo) {
        int interval = Math.max(1, ModernOptSettings.shadowUpdateInterval());
        if (interval <= 1 || ShadowClearRegistry.holdDisabled()) {
            RenderPipeline.setShadowPassActive(false);
            GpuProfiler.end(GpuProfiler.SHADOW);
            return;
        }
        if (modernopt$rememberDrawnFrame(this)) {
            RenderPipeline.shadowHoldNoteDrawn();
        } else {
            RenderPipeline.shadowHoldResetTargets();
        }
        RenderPipeline.setShadowPassActive(false);
        GpuProfiler.end(GpuProfiler.SHADOW);
    }

    /**
     * Re-publishes the matrices of the frame that drew the map, moved with the camera.
     *
     * <p>Iris builds {@code MODELVIEW} from the camera-relative shadow pose: the light rotation
     * plus the grid-snapped camera fraction. The main pass therefore feeds it camera-relative
     * positions, and a held map has to be translated by the camera delta <em>before</em> the light
     * transform. {@code Mheld * T(delta)} does exactly that; translating in light space
     * ({@code T(delta) * Mheld}) would move the lookup along the sun's axes instead.</p>
     */
    private static void modernopt$holdMatricesForThisCamera() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.gameRenderer == null) {
                return;
            }
            if (modernopt$modelViewField == null || modernopt$projectionField == null) {
                return;
            }
            Matrix4f modelView = (Matrix4f) modernopt$modelViewField.get(null);
            Matrix4f projection = (Matrix4f) modernopt$projectionField.get(null);
            if (modelView == null || projection == null) {
                return;
            }
            Vec3 current = minecraft.gameRenderer.getMainCamera().getPosition();
            modelView.set(modernopt$heldModelView).translate(
                    (float) (current.x - modernopt$heldX),
                    (float) (current.y - modernopt$heldY),
                    (float) (current.z - modernopt$heldZ));
            projection.set(modernopt$heldProjection);
        } catch (Throwable throwable) {
            ModernOPT.LOG.warn("[ModernOPT] could not hold the shadow matrices", throwable);
        }
    }

    /** Remembers what the drawn frame used, so skipped frames can keep sampling it. */
    private static boolean modernopt$rememberDrawnFrame(Object shadowRenderer) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.gameRenderer == null) {
                return false;
            }
            modernopt$resolveMatrixFields();
            if (modernopt$modelViewField == null || modernopt$projectionField == null) {
                return false;
            }
            modernopt$heldModelView.set((Matrix4f) modernopt$modelViewField.get(null));
            modernopt$heldProjection.set((Matrix4f) modernopt$projectionField.get(null));
            Camera camera = minecraft.gameRenderer.getMainCamera();
            Vec3 position = camera.getPosition();
            modernopt$heldX = position.x;
            modernopt$heldY = position.y;
            modernopt$heldZ = position.z;
            RenderPipeline.shadowHoldSetCamera(position.x, position.y, position.z);
            RenderPipeline.shadowHoldSetWorldTime(minecraft.level == null ? Long.MIN_VALUE
                    : minecraft.level.getDayTime());
            RenderPipeline.shadowHoldSetCasterRadius(modernopt$shadowDistance(shadowRenderer));
            RenderPipeline.shadowHoldCaptureEntities(minecraft.level, position.x, position.y, position.z);
            return true;
        } catch (Throwable throwable) {
            ModernOPT.LOG.warn("[ModernOPT] could not remember the shadow matrices", throwable);
            return false;
        }
    }

    /**
     * Entity shadow distance of the active pack: {@code distance * distanceRenderMul *
     * entityShadowDistanceMul}. Iris uses the same product for its entity culling frustum, so the
     * hold tracks exactly the casters that can appear in the map instead of a fixed 128 blocks.
     */
    private static double modernopt$shadowDistance(Object shadowRenderer) {
        try {
            if (modernopt$halfPlaneField == null) {
                modernopt$halfPlaneField = shadowRenderer.getClass().getDeclaredField("halfPlaneLength");
                modernopt$halfPlaneField.setAccessible(true);
                modernopt$renderMulField = shadowRenderer.getClass().getDeclaredField("renderDistanceMultiplier");
                modernopt$renderMulField.setAccessible(true);
                modernopt$entityMulField = shadowRenderer.getClass().getDeclaredField("entityShadowDistanceMultiplier");
                modernopt$entityMulField.setAccessible(true);
            }
            float distance = modernopt$halfPlaneField.getFloat(shadowRenderer);
            float renderMul = modernopt$renderMulField.getFloat(shadowRenderer);
            float entityMul = modernopt$entityMulField.getFloat(shadowRenderer);
            if (!(distance > 0.0F)) {
                return 128.0;
            }
            double radius = distance * Math.max(renderMul, 0.0F);
            if (entityMul > 0.0F) {
                radius *= entityMul;
            }
            return radius;
        } catch (Throwable ignored) {
            return 128.0;
        }
    }

    private static void modernopt$resolveMatrixFields() {
        if (modernopt$modelViewField != null && modernopt$projectionField != null) {
            return;
        }
        try {
            Class<?> shadowRenderer = Class.forName("net.irisshaders.iris.shadows.ShadowRenderer");
            modernopt$modelViewField = shadowRenderer.getField("MODELVIEW");
            modernopt$projectionField = shadowRenderer.getField("PROJECTION");
        } catch (Throwable throwable) {
            modernopt$modelViewField = null;
            modernopt$projectionField = null;
        }
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
            try {
                if (modernopt$getGlId == null) {
                    modernopt$getGlId = modernopt$findMethod(framebuffer.getClass(), "getGlId");
                }
                if (modernopt$getGlId != null) {
                    Object id = modernopt$getGlId.invoke(framebuffer);
                    if (id instanceof Integer) {
                        RenderPipeline.shadowHoldSetFramebuffer((Integer) id);
                    }
                }
            } catch (Throwable ignored) {
                // The id is only used to skip the matching clear passes.
            }
            return 0;
        } catch (Throwable throwable) {
            ModernOPT.LOG.warn("[ModernOPT] could not bind Iris' shadow framebuffer", throwable);
            return -1;
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
