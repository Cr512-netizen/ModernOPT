// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.mixin.iris;

import net.cr512.modernopt.ModernOPT;
import net.cr512.modernopt.compat.ShadowClearRegistry;
import net.cr512.modernopt.compat.ShadowImageRegistry;
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
    private static java.lang.reflect.Field modernopt$compositeField;
    private static java.lang.reflect.Method modernopt$getDepthSourceFb;
    private static java.lang.reflect.Method modernopt$bind;
    private static java.lang.reflect.Method modernopt$getGlId;
    private static java.lang.reflect.Method modernopt$renderAll;
    private static boolean modernopt$compositeWarned;
    private static boolean modernopt$framebufferLogged;
    private static boolean modernopt$longTranslationLogged;
    private static long modernopt$lastLog;

    private static java.lang.reflect.Method modernopt$getDepthTexture;
    private static java.lang.reflect.Method modernopt$getTextureId;
    private static long modernopt$probeFrame;
    private static long modernopt$probeDrawnHash;
    private static int modernopt$probeDrawnFar;
    private static boolean modernopt$probeHaveDrawn;
    private static boolean modernopt$probeFailed;

    @Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void modernopt$beginShadowPass(CallbackInfo callbackInfo) {
        ShadowClearRegistry.verifyAfterPipelineLoad();
        double interval = Math.max(1.0, ModernOptSettings.shadowUpdateInterval());
        boolean holdActive = interval > 1.0 && !ShadowClearRegistry.holdDisabled();
        if (!holdActive) {
            // Every-frame shadows are plain Iris behaviour: no hold and no matrix swizzling.
            // The matrices are still captured at RETURN so switching the interval to 2 later
            // cannot hold a map whose matrices were never recorded.
            RenderPipeline.setShadowPassActive(true);
            GpuProfiler.begin(GpuProfiler.SHADOW);
            return;
        }
        if (RenderPipeline.shadowHoldSkipFrame()) {
            // The decision was fixed at beginLevelRendering, before the clear passes ran. Using
            // the same flag here guarantees that a held frame never reaches the shadow pass and
            // that a drawn frame always does, even when a clear pass or the interval counter has
            // already moved on by the time this method is called.
            RenderPipeline.shadowHoldNoteSkipped();
            modernopt$holdMatricesForThisCamera();
            if (modernopt$shouldRefreshShadowComposites()) {
                modernopt$renderShadowComposites(this);
            }
            modernopt$probeDepth(this, true);
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
        modernopt$probeDepth(this, false);
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
            double dx = current.x - modernopt$heldX;
            double dy = current.y - modernopt$heldY;
            double dz = current.z - modernopt$heldZ;
            if (Boolean.getBoolean("modernopt.bench") && !modernopt$longTranslationLogged
                    && (Math.abs(dx) >= 1.0 || Math.abs(dy) >= 1.0 || Math.abs(dz) >= 1.0)) {
                modernopt$longTranslationLogged = true;
                ModernOPT.LOG.warn("[BENCH][SHADOWGL] held translation exceeds one block: "
                        + "({}, {}, {})", dx, dy, dz);
            }
            modelView.set(modernopt$heldModelView).translate(
                    (float) dx, (float) dy, (float) dz);
            projection.set(modernopt$heldProjection);
        } catch (Throwable throwable) {
            ModernOPT.LOG.warn("[ModernOPT] could not hold the shadow matrices", throwable);
        }
    }

    /**
     * Packs whose shadow pass writes custom images need the shadow composite chain re-run from the
     * held depth map. Packs that feed no custom images can reuse the complete previous result and
     * skip the composite cost. When the program hook did not run, keep the conservative
     * always-refresh behaviour rather than risk stale colour targets.
     */
    private static boolean modernopt$shouldRefreshShadowComposites() {
        return ShadowImageRegistry.feedsShadowImages() || !ShadowImageRegistry.learned();
    }

    /**
     * Re-runs Iris' shadow composite chain on a held frame, keeping custom image outputs derived
     * from the shadow map current. The depth map itself is unchanged.
     */
    private static void modernopt$renderShadowComposites(Object shadowRenderer) {
        try {
            if (modernopt$compositeField == null) {
                modernopt$compositeField = shadowRenderer.getClass().getDeclaredField("compositeRenderer");
                modernopt$compositeField.setAccessible(true);
            }
            Object composite = modernopt$compositeField.get(shadowRenderer);
            if (composite == null) {
                return;
            }
            if (modernopt$renderAll == null) {
                modernopt$renderAll = composite.getClass().getMethod("renderAll");
            }
            modernopt$renderAll.invoke(composite);
        } catch (Throwable throwable) {
            if (!modernopt$compositeWarned) {
                modernopt$compositeWarned = true;
                ModernOPT.LOG.warn("[ModernOPT] could not re-run Iris' shadow composites on a held "
                        + "shadow frame; the pack will use its previous composite result", throwable);
            }
        }
    }

    /**
     * Diagnostic for {@code !pairs}: hashes the shadow depth texture each frame and reports
     * whether a held frame still contains the exact map the last drawn frame wrote. A cleared or
     * partially cleared map changes the hash immediately, without depending on screenshot
     * comparison or on the pack's animated cloud shadows.
     */
    private static void modernopt$probeDepth(Object shadowRenderer, boolean held) {
        if (!RenderPipeline.shadowProbeEnabled() || modernopt$probeFailed) {
            return;
        }
        try {
            if (modernopt$targetsField == null) {
                modernopt$targetsField = shadowRenderer.getClass().getDeclaredField("targets");
                modernopt$targetsField.setAccessible(true);
            }
            Object targets = modernopt$targetsField.get(shadowRenderer);
            if (targets == null) {
                return;
            }
            if (modernopt$getDepthTexture == null) {
                modernopt$getDepthTexture = modernopt$findMethod(targets.getClass(), "getDepthTexture");
            }
            Object depthTexture = modernopt$getDepthTexture.invoke(targets);
            if (depthTexture == null) {
                modernopt$probeFailed = true;
                ModernOPT.LOG.warn("[BENCH][PROBE] Iris exposes no shadow depth texture");
                return;
            }
            if (modernopt$getTextureId == null) {
                modernopt$getTextureId = modernopt$findMethod(depthTexture.getClass(), "getTextureId");
            }
            int texture = (Integer) modernopt$getTextureId.invoke(depthTexture);

            int previousActive = org.lwjgl.opengl.GL11C.glGetInteger(
                    org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE);
            org.lwjgl.opengl.GL13C.glActiveTexture(org.lwjgl.opengl.GL13C.GL_TEXTURE0);
            int previousBinding = org.lwjgl.opengl.GL11C.glGetInteger(
                    org.lwjgl.opengl.GL11C.GL_TEXTURE_BINDING_2D);
            try {
                org.lwjgl.opengl.GL11C.glBindTexture(org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, texture);
                int width = org.lwjgl.opengl.GL11C.glGetTexLevelParameteri(
                        org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11C.GL_TEXTURE_WIDTH);
                int height = org.lwjgl.opengl.GL11C.glGetTexLevelParameteri(
                        org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11C.GL_TEXTURE_HEIGHT);
                if (width <= 0 || height <= 0) {
                    throw new IllegalStateException("depth texture has no size");
                }
                java.nio.FloatBuffer pixels = org.lwjgl.BufferUtils.createFloatBuffer(width * height);
                org.lwjgl.opengl.GL11C.glGetTexImage(org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, 0,
                        org.lwjgl.opengl.GL11C.GL_DEPTH_COMPONENT, org.lwjgl.opengl.GL11C.GL_FLOAT, pixels);
                int error = org.lwjgl.opengl.GL11C.glGetError();
                if (error != org.lwjgl.opengl.GL11C.GL_NO_ERROR) {
                    throw new IllegalStateException("glGetTexImage failed: 0x"
                            + Integer.toHexString(error));
                }
                long hash = 0xcbf29ce484222325L;
                int covered = 0;
                for (int index = 0; index < pixels.limit(); index++) {
                    float depth = pixels.get(index);
                    if (depth < 1.0F) {
                        covered++;
                    }
                    hash ^= Math.round(depth * 1_000_000.0);
                    hash *= 0x100000001b3L;
                }
                modernopt$probeFrame++;
                boolean matches = held && modernopt$probeHaveDrawn
                        && hash == modernopt$probeDrawnHash;
                ModernOPT.LOG.info("[BENCH][PROBE] frame={} held={} hash={} covered={} drawnHash={} "
                                + "drawnCovered={} matchesDrawn={}",
                        modernopt$probeFrame, held, Long.toHexString(hash), covered,
                        modernopt$probeHaveDrawn ? Long.toHexString(modernopt$probeDrawnHash) : "-",
                        modernopt$probeHaveDrawn ? modernopt$probeDrawnFar : -1, matches);
                if (!held) {
                    modernopt$probeDrawnHash = hash;
                    modernopt$probeDrawnFar = covered;
                    modernopt$probeHaveDrawn = true;
                }
            } finally {
                org.lwjgl.opengl.GL11C.glBindTexture(org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, previousBinding);
                org.lwjgl.opengl.GL13C.glActiveTexture(previousActive);
            }
        } catch (Throwable throwable) {
            modernopt$probeFailed = true;
            ModernOPT.LOG.warn("[BENCH][PROBE] shadow depth probe failed", throwable);
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
                try {
                    modernopt$halfPlaneField = shadowRenderer.getClass().getDeclaredField("halfPlaneLength");
                    modernopt$halfPlaneField.setAccessible(true);
                    modernopt$renderMulField = shadowRenderer.getClass().getDeclaredField("renderDistanceMultiplier");
                    modernopt$renderMulField.setAccessible(true);
                    modernopt$entityMulField = shadowRenderer.getClass().getDeclaredField("entityShadowDistanceMultiplier");
                    modernopt$entityMulField.setAccessible(true);
                } catch (Throwable missing) {
                    modernopt$halfPlaneField = null;
                    modernopt$renderMulField = null;
                    modernopt$entityMulField = null;
                    return 128.0;
                }
            }
            if (modernopt$renderMulField == null || modernopt$entityMulField == null) {
                return 128.0;
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
                    modernopt$getGlId = modernopt$findMethod(framebuffer.getClass(), "getId");
                    if (modernopt$getGlId == null) {
                        modernopt$getGlId = modernopt$findMethod(framebuffer.getClass(), "getGlId");
                    }
                }
                if (modernopt$getGlId != null) {
                    Object id = modernopt$getGlId.invoke(framebuffer);
                    if (id instanceof Integer) {
                        RenderPipeline.shadowHoldSetFramebuffer((Integer) id);
                        if (Boolean.getBoolean("modernopt.bench") && !modernopt$framebufferLogged) {
                            modernopt$framebufferLogged = true;
                            ModernOPT.LOG.info("[BENCH][SHADOWGL] shadow depth FBO id={}, tagged clears={}",
                                    id, ShadowClearRegistry.size());
                        }
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
