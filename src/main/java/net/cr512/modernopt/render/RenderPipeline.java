// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import net.cr512.modernopt.ModernOPT;
import net.cr512.modernopt.config.ModernOptSettings;
import net.cr512.modernopt.config.UpscalerType;
import net.cr512.modernopt.compat.IrisCompat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.player.LocalPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Drives the whole render scaling pipeline.
 *
 * <p>Frame flow with the mixins attached:</p>
 * <ol>
 *   <li>{@code GameRenderer#renderLevel} HEAD: bind the scaled target and tell
 *       {@code Minecraft#getMainRenderTarget} to answer with it, so the world, entities,
 *       particles, clouds and post effects all land in the smaller/larger image.</li>
 *   <li>Right before {@code LevelRenderer#renderLevel}: for MSR, offset the projection matrix
 *       by a sub-pixel R2 jitter and remember the camera pose.</li>
 *   <li>Right before the hand pass: finish the MSR chain into the real main target, so the
 *       depth buffer is still available for reprojection and the hand is rendered natively.</li>
 *   <li>{@code GameRenderer#render} right before {@code Gui#render}: the remaining filters
 *       resolve MSAA if needed, run the reconstruction filter into the real main target and
 *       bind it again, so the HUD and screens stay at native resolution.</li>
 * </ol>
 */
public final class RenderPipeline {

    private static ScaledRenderTarget scaled;
    /** Minimal target used by plain spatial modes (bilinear / nearest / FSR 1). */
    private static FastWorldTarget fastTarget;
    /** Target the current world frame is drawing into (fast or advanced), or {@code null}. */
    private static RenderTarget frameWorldTarget;
    private static boolean fastWorldActive;
    /** Real {@code Minecraft.mainRenderTarget} while the field is pointing at the world target. */
    private static RenderTarget nativeMainTarget;
    private static boolean worldTargetSwapped;
    private static TextureTarget fsrTarget;
    private static TextureTarget fxaaTarget;
    private static HalfFloatTarget historyA;
    private static HalfFloatTarget historyB;
    private static HalfFloatTarget historyFront;
    private static HalfFloatTarget historyBack;
    private static ShaderLibrary shaders;
    private static boolean shaderFailure;
    private static boolean worldActive;
    /**
     * Whether the last started world frame actually used the offscreen/scaled target. This stays
     * true through the HUD pass so the overlay can name the pass that produced the visible frame;
     * at 100 % with no AA the pipeline is bypassed and the label must say "native" instead of
     * naming an upscaler that never ran.
     */
    private static boolean lastFrameScaled;

    /** Bench diagnostics helper: true while the world is being rendered into the scaled target. */
    public static boolean worldActive() {
        return worldActive;
    }

    /**
     * True while Iris' shadow pass runs. Used to keep the "main render target" redirect from
     * confusing Iris' own main-bound/phase tracking during shadow rendering.
     */
    private static volatile boolean shadowPassActive;

    public static void setShadowPassActive(boolean active) {
        shadowPassActive = active;
    }

    public static boolean shadowPassActive() {
        return shadowPassActive;
    }
    private static boolean loggedIrisSkip;
    private static boolean irisThisFrame;
    private static boolean loggedIrisDepth;
    private static boolean lastIris;
    private static boolean loggedFabulousSkip;
    private static int lastWidth = -1;
    private static int lastHeight = -1;

    /** Temporal upscaling state. */
    private static boolean msrThisFrame;
    private static boolean msrActiveThisFrame;
    private static boolean msrUnsupported;
    private static boolean poseCaptured;
    private static boolean historyValid;
    private static boolean loggedMsrActive;
    private static boolean loggedMsrIris;
    private static boolean loggedMsrMsaa;
    private static boolean loggedMsrFallback;
    private static boolean loggedProjectionGuard;
    private static int msrFailureCount;
    private static boolean msrDepthValidThisFrame = true;
    private static int irisDepthMisses;
    private static Level lastLevel;
    private static UpscalerType lastUpscaler;
    private static int lastUpscalerPercent = -1;
    private static boolean lastUpscalerSsaa;

    // Reusable objects to avoid allocations per frame
    private static final Matrix4f currentProjection = new Matrix4f();
    private static final Matrix4f previousProjection = new Matrix4f();
    private static final Matrix4f currentView = new Matrix4f();
    private static final Matrix4f previousView = new Matrix4f();
    private static final Matrix4f inverseProjection = new Matrix4f();
    private static final Matrix4f tempMatrix = new Matrix4f();
    private static final Quaternionf tempQuat = new Quaternionf();
    private static final Vector3f tempVec3 = new Vector3f();
    
    // Cache for inverse projection to avoid recomputation when projection unchanged
    private static Matrix4f cachedProjection = null;
    private static Matrix4f cachedInverseProjection = null;
    private static double currentCameraX;
    private static double currentCameraY;
    private static double currentCameraZ;
    private static double previousCameraX;
    private static double previousCameraY;
    private static double previousCameraZ;
    private static float jitterX;
    private static float jitterY;
    private static int jitterIndex;

    // R2 (Sobol) jitter sequence - better distribution than Halton, less clustering
    private static final float[][] R2_JITTER = new float[64][2];

    // MSR2 state
    private static boolean msr2ThisFrame;
    private static boolean msr2ActiveThisFrame;
    private static MSR2HistoryTarget msr2History;
    private static HalfFloatTarget msr2CurrentColor;
    private static boolean msrShaderFailure;
    private static boolean msrHistoryValid;
    private static int msrFrameIndex;
    private static MotionVectorRenderer motionVectors;
    private static long motionFrameIndex;
    static {
        float phi = 1.324717957244746f; // plastic constant (real root of x^3 = x + 1)
        for (int i = 0; i < 64; i++) {
            float f = (float) i;
            R2_JITTER[i][0] = (0.5f + f * phi) % 1.0f - 0.5f;
            R2_JITTER[i][1] = (0.5f + f * phi * phi) % 1.0f - 0.5f;
        }
    }

    private static int screenshotTextureUnit = -1;
    private static int screenshotTextureId;

    // Cached settings to avoid repeated refresh calls
    private static int cachedRenderScale = -1;
    private static boolean cachedSsaa = false;
    private static boolean cachedFxaa = false;
    private static int cachedMsaaSamples = 1;
    private static UpscalerType cachedUpscaler = UpscalerType.FSR1;
    private static int cachedFsrEasuSharpness = 0;
    private static int cachedFsrRcasSharpness = 25;
    private static int cachedSoftness = 35;
    private static boolean cachedScaleWholeWindow;
    private static boolean wholeWindowActive;

    // MSR2 cached settings
    private static boolean cachedMsrEnabled = false;
    private static double cachedMsrVarianceScale = 2.0;
    private static boolean cachedMsrEdgeAware = true;
    private static int cachedMsrSharpness = 30;
    private static double cachedMsrDepthThreshold = 0.02;
    private static double cachedMsrHistoryWeight = 0.85;

    // DRS cached settings
    private static boolean cachedMsrDynamicRes = true;
    private static int cachedMsrTemporalMinFps = 40;
    private static int cachedMsrScaleStep = 5;
    private static int cachedMsrMinScale = 50;

    /** Bench-only diagnostic counter for per-frame GL logging. */
    private static int dbgFrameCounter;

    /**
     * True while the world is being rendered into the scaled target. While set, {@code Window}
     * reports the scaled size, so every subsystem that sizes its own buffers from the window
     * (Iris' post-processing chain, vanilla post effects, helper targets) allocates and draws at
     * the internal resolution instead of the native one. The HUD and the hand stay native.
     */
    private static boolean windowScaled;

    /**
     * Real framebuffer size, captured right before {@link #windowScaled} becomes {@code true}.
     * Once the override is active {@code Window#getWidth()} reports the internal size, so the
     * original values have to be remembered for the stale-viewport check.
     */
    private static int realWindowWidth;
    private static int realWindowHeight;

    /** Camera-movement resolution scaling: 70% becomes 60% while the view changes. */
    private static final float MOVING_RESOLUTION_FACTOR = 0.85F;
    private static final int MOVING_RESOLUTION_STILL_FRAMES = 20;
    private static final double MOVING_RESOLUTION_POS_SQ = 0.0025;
    private static final float MOVING_RESOLUTION_ROT_DEG = 0.2F;
    private static boolean movingResolutionPoseValid;
    private static double movingResolutionLastX;
    private static double movingResolutionLastY;
    private static double movingResolutionLastZ;
    private static float movingResolutionLastYaw;
    private static float movingResolutionLastPitch;
    private static boolean movingResolutionActive;
    private static int movingResolutionStillFrames;
    private static Object movingResolutionLevel;
    private static int lastLoggedRenderScale = -1;

    /**
     * Set when the world was upscaled and Iris owns the hand pass. Iris draws the first-person
     * hand inside the world pipeline (it cancels vanilla's call), where it ends up in the gbuffer
     * that is then composited at the internal resolution, so at render scales below 100% the hand
     * never reaches the upscaled image. Instead the hand is drawn once more, at native
     * resolution, right where vanilla would have drawn it: after the reconstruction and after the
     * depth buffer of the main target has been cleared.
     */
    private static boolean deferredHandThisFrame;

    /** Whether the deferred native-resolution hand pass should run this frame. */
    public static boolean shouldRenderDeferredHand() {
        return deferredHandThisFrame;
    }

    /** Returns the width {@code Window#getWidth} should report, or {@code -1} for the real one. */
    public static int windowWidthOverride() {
        // In whole-window mode the real main target is the small frame, so Window must describe
        // that frame (not a leftover world target of a different size) or the GUI is laid out for
        // the wrong resolution and the final blit only fills a corner of the window.
        if (wholeWindowActive) {
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            return main != null ? main.width : -1;
        }
        RenderTarget target = activeWorldTarget();
        return windowScaled && target != null ? target.viewWidth : -1;
    }
    /** Returns the height {@code Window#getHeight} should report, or {@code -1} for the real one. */
    public static int windowHeightOverride() {
        if (wholeWindowActive) {
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            return main != null ? main.height : -1;
        }
        RenderTarget target = activeWorldTarget();
        return windowScaled && target != null ? target.viewHeight : -1;
    }

    /** Target used by the current (or last) world frame. */
    public static RenderTarget activeWorldTarget() {
        if (frameWorldTarget != null) {
            return frameWorldTarget;
        }
        if (fastWorldActive && fastTarget != null) {
            return fastTarget;
        }
        return scaled;
    }

    /** Real framebuffer width, captured when the world frame starts. */
    public static int realWindowWidth() {
        return realWindowWidth;
    }

    /** Real framebuffer height, captured when the world frame starts. */
    public static int realWindowHeight() {
        return realWindowHeight;
    }

    // DRS state
    private static int drsCurrentPercent = -1;
    private static long drsLastAdjustNanos = 0;
    private static long drsFrameStartNanos = 0;
    private static long drsAccumulatedFrameTimeNs = 0;
    private static int drsSampleCount = 0;
    private static int drsStableFrames = 0;
    private static final long DRS_ADJUST_INTERVAL_NS = 1_000_000_000L; // 1 second
    private static final int DRS_STABLE_FRAMES_NEEDED = 60;
    private static final int DRS_SAMPLE_FRAMES = 30;

    // Compute shader support (GL 4.3+)
    private static boolean computeAvailable = false;
    private static Msr2TemporalCompute msrComputeProgram;
    private static boolean msrComputeFailed;

    /** Polyphase Lanczos tables shared with {@code msr_temporal.comp} (65 phases, 6 taps). */
    private static final int LANCZOS_PHASES = 65;
    private static final int LANCZOS_TAPS = 6;
    private static final float[] LANCZOS_WEIGHTS_2 = buildLanczosWeights(2);
    private static final float[] LANCZOS_WEIGHTS_3 = buildLanczosWeights(3);
    private static int lanczosTableTexture;

    private RenderPipeline() {
    }

    /** Internal world resolution, for the overlay and for logging. */
    public static int internalWidth() {
        RenderTarget target = activeWorldTarget();
        return target != null ? target.width : -1;
    }

    public static int internalHeight() {
        RenderTarget target = activeWorldTarget();
        return target != null ? target.height : -1;
    }

    public static int activeSamples() {
        return fastWorldActive ? 1 : (scaled != null ? scaled.activeSamples() : 1);
    }

    /**
     * Label for the overlay: the upscaler that actually produced the last world frame.
     * Keeps the UI honest when MSR fell back to FSR 1 (SSAA or a pass failure).
     */
    public static String activeUpscalerLabel() {
        UpscalerType requested = ModernOptSettings.upscaler();
        if (!lastFrameScaled && !offscreenRequested(requested)) {
            // At 100 % with no AA the world renders straight into the main target. Naming an
            // upscaler here would claim a pass that never ran; this also covers MSR2, whose
            // 100 % path is an intentional passthrough.
            return "native";
        }
        if (requested == UpscalerType.MSR && !msrActiveThisFrame) {
            return withLanczos(ModernOptSettings.ssaa() ? "MSR off (SSAA)" : "MSR off (FSR 1)");
        }
        if (requested == UpscalerType.MSR && !msrDepthValidThisFrame) {
            return withLanczos("MSR (no depth)");
        }
        if (requested == UpscalerType.FSR1 || requested == UpscalerType.MSR2) {
            return withLanczos(requested.label());
        }
        return requested.label();
    }

    /** True when the requested settings can use the offscreen target on the next frame. */
    private static boolean offscreenRequested(UpscalerType requested) {
        if (requested == UpscalerType.MSR2
                && ModernOptSettings.renderScalePercent() >= 100
                && !ModernOptSettings.ssaa()) {
            // beginWorldFrame short-circuits MSR2 at 100 % before the FXAA/MSAA checks.
            return false;
        }
        return RenderScaleMath.needsOffscreenPass(ModernOptSettings.renderScalePercent(),
                ModernOptSettings.ssaa(), ModernOptSettings.msaa().samples() > 1,
                ModernOptSettings.fxaa());
    }

    /** Shows the shared Lanczos toggle in the overlay and benchmark state label. */
    private static String withLanczos(String label) {
        if (!ModernOptSettings.msr2Lanczos()) {
            return label;
        }
        return label + (ModernOptSettings.msr2Lanczos3() ? " + L3" : " + L2");
    }

    /**
     * Advanced modes still use the call-time redirect so their existing MSAA/FP16/temporal
     * plumbing keeps seeing the scaled target. The fast path never reaches this: its target is
     * installed in the real field instead.
     */
    public static RenderTarget redirectedWorldTarget() {
        if (!worldActive || fastWorldActive) {
            return null;
        }
        return frameWorldTarget;
    }

    /** The target the world should render into, or {@code null} when vanilla rendering is fine. */
    @Deprecated
    public static RenderTarget currentWorldTarget() {
        return redirectedWorldTarget();
    }

    /** Get the current projection matrix (captured during jitterProjection). */
    public static Matrix4f getCurrentProjection() {
        return currentProjection;
    }

    /** Get the current view matrix (captured during jitterProjection). */
    public static Matrix4f getCurrentView() {
        return currentView;
    }

    /**
     * Records one rendered entity for the MSR2 motion-vector buffer.
     *
     * <p>{@code x},{@code y},{@code z} are the camera-relative coordinates Minecraft passes to
     * {@code EntityRenderDispatcher}; the current view matrix is rotation-only, so world
     * coordinates must not be used here.</p>
     */
    public static void recordEntityMotion(Entity entity, double x, double y, double z) {
        if (motionVectors == null || !msr2ThisFrame || shadowPassActive
                || !ModernOptSettings.msr2MotionVectors()) {
            return;
        }
        motionVectors.record(entity, x, y, z, currentView, currentProjection);
    }

    /**
     * Tracks whether the camera is moving and computes the resolution for this frame.
     *
     * <p>The drop is a single step, not a per-frame regulator: 70% becomes 60% while the view
     * changes, and the configured value is restored after a short stillness period. A small
     * hysteresis keeps a slow walk or a tiny mouse movement from resizing the target every frame.
     * </p>
     */
    private static void updateMovingResolution(Minecraft minecraft) {
        if (movingResolutionLevel != minecraft.level) {
            movingResolutionLevel = minecraft.level;
            movingResolutionPoseValid = false;
            movingResolutionActive = false;
            movingResolutionStillFrames = 0;
        }
        if (!ModernOptSettings.dynamicMovingResolution()) {
            movingResolutionActive = false;
            movingResolutionStillFrames = 0;
            movingResolutionPoseValid = false;
            lastLoggedRenderScale = -1;
            return;
        }
        Camera camera = minecraft.gameRenderer == null ? null : minecraft.gameRenderer.getMainCamera();
        if (camera == null) {
            movingResolutionActive = false;
            movingResolutionPoseValid = false;
            return;
        }
        net.minecraft.world.phys.Vec3 position = camera.getPosition();
        float yaw = camera.getYRot();
        float pitch = camera.getXRot();
        if (!movingResolutionPoseValid) {
            movingResolutionPoseValid = true;
            movingResolutionActive = false;
            movingResolutionStillFrames = 0;
        } else {
            double dx = position.x - movingResolutionLastX;
            double dy = position.y - movingResolutionLastY;
            double dz = position.z - movingResolutionLastZ;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            float dyaw = Math.abs(yaw - movingResolutionLastYaw);
            if (dyaw > 180.0F) {
                dyaw = 360.0F - dyaw;
            }
            float dpitch = Math.abs(pitch - movingResolutionLastPitch);
            boolean moved = distanceSq > MOVING_RESOLUTION_POS_SQ
                    || dyaw > MOVING_RESOLUTION_ROT_DEG
                    || dpitch > MOVING_RESOLUTION_ROT_DEG;
            if (moved) {
                movingResolutionActive = true;
                movingResolutionStillFrames = 0;
            } else if (++movingResolutionStillFrames >= MOVING_RESOLUTION_STILL_FRAMES) {
                movingResolutionActive = false;
            }
        }
        movingResolutionLastX = position.x;
        movingResolutionLastY = position.y;
        movingResolutionLastZ = position.z;
        movingResolutionLastYaw = yaw;
        movingResolutionLastPitch = pitch;
    }

    /** Configured scale, reduced by 15% while the camera is moving. */
    private static int effectiveRenderScale() {
        if (!ModernOptSettings.dynamicMovingResolution() || !movingResolutionActive) {
            return cachedRenderScale;
        }
        return Math.max(25, Math.round(cachedRenderScale * MOVING_RESOLUTION_FACTOR));
    }

    private static void logMovingResolution(int effective) {
        if (!ModernOptSettings.dynamicMovingResolution()) {
            lastLoggedRenderScale = -1;
            return;
        }
        if (effective != lastLoggedRenderScale) {
            if (lastLoggedRenderScale >= 0) {
                ModernOPT.LOG.debug("[ModernOPT] dynamic resolution {}% -> {}%",
                        lastLoggedRenderScale, effective);
            }
            lastLoggedRenderScale = effective;
        }
    }

    /** Called at the head of {@code GameRenderer#renderLevel}. */
    public static void beginWorldFrame() {
        WorldCpuProfiler.noteWorldFrame();
        WorldCpuProfiler.maybeLog();
        shadowFrameToken++;
        shadowClearWindow = false;
        shadowHoldSkipFrame = false;
        lastFrameScaled = false;
        worldActive = false;
        msrThisFrame = false;
        msr2ThisFrame = false;
        msr2ActiveThisFrame = false;
        poseCaptured = false;
        // Never let the window-size override leak from a frame that was skipped or aborted:
        // otherwise the next frame reads the *scaled* width back and halves the target again.
        windowScaled = false;
        frameWorldTarget = null;
        // A previous frame may have been aborted between the field swap and its restore (screen
        // change, exception, cancelled renderLevel). Start from the real target every time.
        restoreMainTarget();

        GpuProfiler.maybeLog();
        RenderStageProfiler.maybeLog();

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.noRender) {
            restoreWindowSizedMainTarget(minecraft);
            resetHistory();
            return;
        }

        // Cache settings once per frame
        ModernOptSettings.refresh();
        if (!ModernOptSettings.enabled()) {
            // A whole-window frame may have left the main target small; disabling the mod must
            // restore the real window size instead of leaving the game locked at the old scale.
            restoreWindowSizedMainTarget(minecraft);
            resetHistory();
            return;
        }

        // The hold decision is intentionally made only from the update interval and the camera
        // block, checked again at beginLevelRendering after the current camera is installed.
        // World time and entity motion do not force extra redraws: an irregular extra draw makes
        // a held sequence look like flicker, while the interval already refreshes both.

        // Fast settings cache - only refresh when actually changed
        cacheSettings();
        // Detection alone does not compile the program: the first switch to MSR2 after launch
        // used to fall through to the spatial fallback until a resource reload. Load it here.
        if (cachedMsrEnabled && computeAvailable && msrComputeProgram == null && !msrComputeFailed) {
            if (minecraft.getResourceManager() instanceof ResourceProvider resources) {
                loadMsrCompute(resources);
            }
        }
        double shadowInterval = ModernOptSettings.shadowUpdateInterval();
        if (shadowInterval != shadowStatsInterval) {
            shadowStatsInterval = shadowInterval;
            // Interval changes are a policy change: drop the held map and matrices so the next
            // shadow pass is a real draw. This also covers 1 -> 2, where no matrices used to be
            // captured at all and the first held frame could use identity/stale matrices.
            shadowHoldResetTargets();
        }
        updateMovingResolution(minecraft);

        if (cachedScaleWholeWindow) {
            beginWholeWindowFrame(minecraft);
            return;
        }
        restoreWindowSizedMainTarget(minecraft);
        
        // Dynamic Resolution Scaling (DRS) for MSR
        if (cachedMsrEnabled && cachedMsrDynamicRes) {
            applyDrs();
        }

        int windowWidth = minecraft.getWindow().getWidth();
        int windowHeight = minecraft.getWindow().getHeight();
        if (windowWidth <= 0 || windowHeight <= 0) {
            resetHistory();
            return;
        }

        if (minecraft.level != lastLevel) {
            lastLevel = minecraft.level;
            resetHistory();
            resetMsr2History();
            lastUpscaler = null;
            // Do not compare the first camera pose of a new world with the previous dimension.
            movingResolutionPoseValid = false;
            movingResolutionActive = false;
            movingResolutionStillFrames = 0;
            // Do not carry a held shadow map across a dimension/world change.
            shadowHoldResetTargets();
        }

        UpscalerType requested = cachedUpscaler;
        boolean iris = IrisCompat.isShaderPackInUse();
        irisThisFrame = iris;
        if (iris != lastIris) {
            lastIris = iris;
            resetHistory();
            resetMsr2History();
        }
        boolean msrWanted = requested == UpscalerType.MSR && !cachedSsaa;
        if (requested == UpscalerType.MSR && iris && !loggedMsrIris) {
            loggedMsrIris = true;
            ModernOPT.LOG.info("[ModernOPT] MSR is active with the Iris shader pack: using Iris depth for "
                    + "reprojection and letting the pack's own temporal filter handle sub-pixel jitter");
        }
        if (msrWanted && cachedMsaaSamples > 1 && !loggedMsrMsaa) {
            loggedMsrMsaa = true;
            ModernOPT.LOG.info("[ModernOPT] MSAA is off in MSR mode (the temporal pass needs a single sampled depth buffer)");
        }

        int samples = cachedMsaaSamples;
        if (samples > 1) {
            if (isFabulous(minecraft)) {
                samples = 1;
                if (!loggedFabulousSkip) {
                    loggedFabulousSkip = true;
                    ModernOPT.LOG.info("[ModernOPT] Fabulous graphics mode is on, MSAA is disabled (it needs a single sampled depth buffer)");
                }
            } else if (iris) {
                samples = 1;
                logIrisOnce("MSAA is off while an Iris shader pack is active");
            }
        }
        if (msrWanted) {
            samples = 1;
        }

        if (iris) {
            logIrisOnce("shader pack detected, scaling the Iris pipeline as well");
        }

        if (msrWanted && (!ensureShaders(minecraft) || shaders == null || !shaders.hasTemporal())) {
            if (shaders == null || !shaders.isValid()) {
                return;
            }
            msrUnsupported = true;
            msrWanted = false;
            if (!loggedMsrFallback) {
                loggedMsrFallback = true;
                ModernOPT.LOG.warn("[ModernOPT] MSR temporal pass unavailable, falling back to FSR 1");
            }
        }

        int effectiveScale = effectiveRenderScale();
        logMovingResolution(effectiveScale);
        int[] size = RenderScaleMath.targetSize(windowWidth, windowHeight, effectiveScale,
                cachedSsaa, RenderSystem.maxSupportedTextureSize());

        // MSR2 takes priority over other upscalers when enabled
        if (cachedMsrEnabled) {
            // MSR uses the same scaled target but different reconstruction
            // Force single sample for MSR temporal pass
            samples = 1;
            msrWanted = false;
            // At 100% render scale there is nothing to reconstruct: MSR must be a pure
            // passthrough here so it can never be slower than native rendering.
            if (size[0] >= windowWidth && size[1] >= windowHeight) {
                resetHistory();
                resetMsr2History();
                rememberUpscalerSettings(requested);
                return;
            }
        }

        boolean useful = size[0] != windowWidth || size[1] != windowHeight || samples > 1
                || cachedFxaa || msrWanted || cachedMsrEnabled;
        if (!useful) {
            resetHistory();
            resetMsr2History();
            rememberUpscalerSettings(requested);
            return;
        }

        ensureScaledTarget(size[0], size[1], samples);
        if (scaled == null) {
            resetHistory();
            resetMsr2History();
            return;
        }

        // Reset temporal history before allocating/reallocating the MSR targets. Resetting after
        // ensureMSR2HistoryTargets() would destroy the freshly created ping-pong pair.
        int outputWidth = minecraft.getMainRenderTarget().width;
        int outputHeight = minecraft.getMainRenderTarget().height;
        boolean settingsChanged = requested != lastUpscaler
                || cachedRenderScale != lastUpscalerPercent
                || cachedSsaa != lastUpscalerSsaa;
        if (requested != lastUpscaler) {
            ModernOPT.LOG.info("[ModernOPT] upscaler {} -> {} (Lanczos {})",
                    lastUpscaler, requested,
                    ModernOptSettings.msr2Lanczos() ? "on" : "off");
        }
        rememberUpscalerSettings(requested);
        if (!msrWanted) {
            resetHistory();
        } else if (settingsChanged) {
            resetHistory();
        }
        if (settingsChanged) {
            // MSR history must not survive a scale or upscaler change either.
            resetMsr2History();
        }

        // Initialize MSR history targets if needed. The colour/depth history now lives at the
        // output resolution (RGBA16F + R16UI), so the temporal pass does not throw away the
        // detail it just accumulated when the spatial pass upsamples.
        if (cachedMsrEnabled) {
            ensureMSR2HistoryTargets(outputWidth, outputHeight);
            msr2ThisFrame = true;
            msrWanted = false;
        }

        scaled.setLinearFilter(requested != UpscalerType.NEAREST);
        msrThisFrame = msrWanted;
        // Plain spatial modes do not need the MSAA/FP16/history plumbing of ScaledRenderTarget.
        // Use the minimal target for those and keep the advanced target dormant as a fallback.
        boolean useFastTarget = !cachedMsrEnabled
                && !msrWanted
                && !cachedSsaa
                && samples == 1
                && !ModernOptSettings.fp16World();
        if (useFastTarget) {
            ensureFastTarget(size[0], size[1]);
            useFastTarget = fastTarget != null;
        }
        RenderTarget worldTarget = useFastTarget ? fastTarget : scaled;
        frameWorldTarget = worldTarget;
        fastWorldActive = useFastTarget;
        if (worldTarget != null) {
            int filterMode = requested != UpscalerType.NEAREST
                    ? GL11C.GL_LINEAR : GL11C.GL_NEAREST;
            if (worldTarget.filterMode != filterMode) {
                worldTarget.setFilterMode(filterMode);
            }
        }
        if (msrWanted && !msrUnsupported) {
            // The temporal history lives in output resolution. Allocate it here: switching to
            // MSR at runtime used to reach reconstructMsr() with null history targets and
            // silently fall back to FSR 1 for the rest of the session.
            ensureHistoryTargets(outputWidth, outputHeight);
        }
        worldActive = true;
        lastFrameScaled = true;
        motionFrameIndex++;
        if (cachedMsrEnabled && ModernOptSettings.msr2MotionVectors()
                && motionVectors != null && worldTarget != null) {
            motionVectors.beginFrame(motionFrameIndex, worldTarget.width, worldTarget.height);
        }
        if (useFastTarget) {
            // Plain spatial modes: install the small target in the real field. This is the hot
            // path and must not pay for a per-call getter redirect.
            swapMainTarget(minecraft, worldTarget);
        }
        if (worldTarget != null) {
            // The frame-start clear in Minecraft#run targeted the native main target. Clear the
            // small target here so plain rendering and packs that do not cover every pixel both
            // start from a valid colour/depth buffer.
            worldTarget.bindWrite(true);
            GlStateManager._clearColor(0.0F, 0.0F, 0.0F, 0.0F);
            GlStateManager._clear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        }
        realWindowWidth = minecraft.getWindow().getWidth();
        realWindowHeight = minecraft.getWindow().getHeight();
        // Only the advanced path keeps the window override; the fast path leaves the window
        // native and relies on the swapped main target.
        windowScaled = !useFastTarget;
        wholeWindowActive = cachedScaleWholeWindow
                && worldTarget != null
                && (worldTarget.width < realWindowWidth || worldTarget.height < realWindowHeight);
        if (wholeWindowActive && worldTarget != null) {
            // The scaled target *is* this frame's target; one blit at the end of Minecraft#run
            // puts it on the window. Linear filtering keeps the upscale smooth.
            worldTarget.setFilterMode(GL11C.GL_LINEAR);
        }
        GpuProfiler.beginCpuWorld();
        GpuProfiler.begin(GpuProfiler.WORLD);
        if ((++dbgFrameCounter % 240) == 0) {
            ModernOPT.LOG.info("[BENCH][GL] begin {}", debugGlState());
        }

        if (msrThisFrame && !loggedMsrActive) {
            loggedMsrActive = true;
            ModernOPT.LOG.info("[ModernOPT] MSR temporal upscaling active ({}x{} -> {}x{}, camera-reconstructed motion)",
                    scaled.width, scaled.height, outputWidth, outputHeight);
        }
    }
    
    /** Apply Dynamic Resolution Scaling based on real frame time. */
    private static void applyDrs() {
        if (drsCurrentPercent < 0) {
            drsCurrentPercent = cachedRenderScale;
        }

        // Accumulate real frame time
        long now = System.nanoTime();
        if (drsFrameStartNanos == 0) {
            drsFrameStartNanos = now;
            return;
        }
        drsAccumulatedFrameTimeNs += (now - drsFrameStartNanos);
        drsFrameStartNanos = now;
        drsSampleCount++;

        if (drsSampleCount < DRS_SAMPLE_FRAMES) {
            return;
        }

        // Compute average frame time and FPS over the sample window
        double avgFrameTimeMs = (drsAccumulatedFrameTimeNs / (double) drsSampleCount) / 1_000_000.0;
        double fps = 1000.0 / avgFrameTimeMs;
        drsSampleCount = 0;
        drsAccumulatedFrameTimeNs = 0;

        long adjustNow = System.nanoTime();
        if (adjustNow - drsLastAdjustNanos < DRS_ADJUST_INTERVAL_NS) {
            return;
        }
        drsLastAdjustNanos = adjustNow;

        int minScale = cachedMsrMinScale;
        int step = cachedMsrScaleStep;
        int tempMinFps = cachedMsrTemporalMinFps;

        // Lower scale immediately if FPS drops below threshold
        if (fps < tempMinFps && drsCurrentPercent > minScale) {
            drsCurrentPercent = Math.max(minScale, drsCurrentPercent - step);
            drsStableFrames = 0;
        } else if (fps >= tempMinFps * 1.2) {
            // Raise scale gradually when stable (1.2x headroom)
            drsStableFrames++;
            if (drsStableFrames >= DRS_STABLE_FRAMES_NEEDED && drsCurrentPercent < cachedRenderScale) {
                drsCurrentPercent = Math.min(cachedRenderScale, drsCurrentPercent + step);
                drsStableFrames = 0;
            }
        } else {
            drsStableFrames = 0;
        }

        // Clamp to valid range
        drsCurrentPercent = Math.max(minScale, Math.min(100, drsCurrentPercent));

        // Apply to cachedRenderScale if DRS changed it
        if (drsCurrentPercent != cachedRenderScale) {
            cachedRenderScale = drsCurrentPercent;
            ModernOPT.LOG.info("[ModernOPT] DRS: adjusted render scale to {}% (FPS: {:.1f})", drsCurrentPercent, fps);
        }
    }
    
    /** Cache settings to avoid repeated field access */
    private static void cacheSettings() {
        cachedRenderScale = ModernOptSettings.renderScalePercent();
        cachedSsaa = ModernOptSettings.ssaa();
        cachedFxaa = ModernOptSettings.fxaa();
        cachedMsaaSamples = ModernOptSettings.msaa().samples();
        cachedUpscaler = ModernOptSettings.upscaler();
        cachedFsrEasuSharpness = ModernOptSettings.fsrEasuSharpness();
        cachedFsrRcasSharpness = ModernOptSettings.fsrRcasSharpness();
        cachedSoftness = ModernOptSettings.softness();
        cachedScaleWholeWindow = ModernOptSettings.scaleWholeWindow();

        // MSR2
        cachedMsrEnabled = cachedUpscaler == UpscalerType.MSR2;
        cachedMsrVarianceScale = ModernOptSettings.msr2VarianceScale();
        cachedMsrEdgeAware = ModernOptSettings.msr2EdgeAware();
        cachedMsrSharpness = ModernOptSettings.msr2Sharpness();
        cachedMsrDepthThreshold = ModernOptSettings.msr2DepthThreshold();
        cachedMsrHistoryWeight = ModernOptSettings.msr2HistoryWeight();

        // DRS
        cachedMsrDynamicRes = ModernOptSettings.msr2DynamicRes();
        cachedMsrTemporalMinFps = ModernOptSettings.msr2TemporalMinFps();
        cachedMsrScaleStep = ModernOptSettings.msr2ScaleStep();
        cachedMsrMinScale = ModernOptSettings.msr2MinScale();
        if (cachedMsrEnabled && !computeAvailable) {
            try {
                computeAvailable = org.lwjgl.opengl.GL.getCapabilities().OpenGL43;
                if (computeAvailable) {
                    ModernOPT.LOG.info("[ModernOPT] MSR: GL 4.3+ compute shaders available");
                } else {
                    ModernOPT.LOG.info("[ModernOPT] MSR: GL 4.3+ not available, using spatial fallback");
                }
            } catch (Throwable t) {
                computeAvailable = false;
                ModernOPT.LOG.warn("[ModernOPT] MSR: compute detection failed", t);
            }
        }
    }

    /**
     * Applies the MSR sub-pixel jitter to the projection matrix that is about to be used by the
     * world pass and records the pose needed for reprojection.
     *
     * <p>Called from the {@code LevelRenderer#renderLevel} call site, i.e. after vanilla applied
     * view bobbing and nausea to the projection. The returned copy is only used by the world
     * pass; the hand pass gets the untouched matrix.</p>
     */
    public static Matrix4f jitterProjection(Matrix4f projection, Camera camera) {
        if (!msrThisFrame && !msr2ThisFrame || scaled == null) {
            return projection;
        }

        // Capture camera pose for both MSR (reprojection + jitter) and MSR2 (reprojection + jitter).
        tempQuat.set(camera.rotation()).conjugate();
        currentView.set(tempQuat);
        currentProjection.set(projection);
        currentCameraX = camera.getPosition().x;
        currentCameraY = camera.getPosition().y;
        currentCameraZ = camera.getPosition().z;

        if (!isUsableProjection(projection)) {
            resetHistory();
            jitterX = 0.0F;
            jitterY = 0.0F;
            if (!loggedProjectionGuard) {
                loggedProjectionGuard = true;
                ModernOPT.LOG.debug("[ModernOPT] skipping MSR on a degenerate projection matrix (first frame)");
            }
            return projection;
        }

        if (irisThisFrame && !msr2ThisFrame) {
            // MSR + Iris: the pack may already run its own temporal AA, so do not add a second
            // jitter. MSR2 keeps its R2 jitter because the output-resolution temporal pass is
            // the one that resolves it; the pack's TAA runs before that pass.
            jitterX = 0.0F;
            jitterY = 0.0F;
            poseCaptured = true;
            return projection;
        }

        int index = jitterIndex;
        jitterIndex = (jitterIndex + 1) & 63; // 64-sample R2 sequence
        float offsetX = R2_JITTER[index][0];
        float offsetY = R2_JITTER[index][1];
        jitterX = 2.0F * offsetX / scaled.width;
        jitterY = 2.0F * offsetY / scaled.height;

        tempMatrix.set(projection);
        // clip.x += jitter * clip.w is equivalent to row0 += jitter * row3; for a perspective
        // matrix row3.z is -1, hence the subtraction of the z coefficients.
        tempMatrix.m20(tempMatrix.m20() - jitterX);
        tempMatrix.m21(tempMatrix.m21() - jitterY);
        RenderSystem.setProjectionMatrix(tempMatrix, VertexSorting.DISTANCE_TO_ORIGIN);
        poseCaptured = true;
        return tempMatrix;
    }

    /**
     * Called right before the vanilla hand pass. Both temporal upscalers have to finish here:
     * the hand pass clears the depth buffer, which the temporal pass still needs, and the hand
     * itself should stay at native resolution. Previously only MSR stopped here; MSR ran
     * after the hand and therefore read an already-cleared (all-sky) depth buffer.
     */
    public static void finishBeforeHand() {
        if (!worldActive || frameWorldTarget == null) {
            return;
        }
        // Finish for every upscaler. The hand is rendered into whatever target is bound right
        // after this point; letting it run before the reconstruction would draw it into the
        // small target and then overwrite it with the upscaled world (the hand simply
        // disappeared in FSR 1 / bilinear / off modes).
        finishWorldFrame();
    }

    /** Called right before {@code Gui#render}, i.e. after the world and before the HUD. */
    public static void endWorldFrame() {
        finishWorldFrame();
    }

    // ------------------------------------------------------------------
    // Downscaled HUD pass
    // ------------------------------------------------------------------

    private static RenderTarget hudTarget;
    private static boolean hudActive;

    /** True while the HUD is being rendered into the downscaled GUI target. */
    public static boolean hudActive() {
        return hudActive;
    }

    /** The downscaled GUI target while the HUD is being rendered, otherwise {@code null}. */
    public static RenderTarget activeHudTarget() {
        return hudActive ? hudTarget : null;
    }

    /**
     * Redirects the GUI pass into a downscaled target when {@code hudScalePercent} is below 100.
     *
     * <p>A full-frame scaler does the same by making the window report the scaled size for the
     * whole frame. ModernOPT keeps the window native (so the game window and mouse stay untouched)
     * and instead binds a dedicated GUI target of {@code hudScalePercent} of the window; the HUD is
     * laid out in the same GUI units as before and upscaled with the rest of the frame, so it looks
     * identical in size, only softer. Costs one extra textured blit at the end of the frame.</p>
     */
    public static void beginHudFrame(Minecraft minecraft) {
        hudActive = false;
        if (wholeWindowActive) {
            // The HUD is already rendered at the internal resolution in this mode.
            return;
        }
        if (!ModernOptSettings.enabled()) {
            return;
        }
        int percent = Math.max(50, Math.min(100, ModernOptSettings.hudScalePercent()));
        if (percent >= 100) {
            return;
        }
        RenderTarget main = minecraft.getMainRenderTarget();
        if (main == null || main.width <= 0 || main.height <= 0) {
            return;
        }
        int width = Math.max(1, (int) Math.round(main.width * (percent / 100.0)));
        int height = Math.max(1, (int) Math.round(main.height * (percent / 100.0)));
        try {
            if (hudTarget == null) {
                hudTarget = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            }
            if (hudTarget.width != width || hudTarget.height != height) {
                hudTarget.resize(width, height, Minecraft.ON_OSX);
            }
        } catch (Throwable throwable) {
            ModernOPT.LOG.warn("[ModernOPT] cannot allocate the {}x{} HUD target, HUD scaling disabled",
                    width, height, throwable);
            hudTarget = null;
            return;
        }
        hudTarget.bindWrite(true);
        // Transparent clear: the HUD is composited over the already upscaled world.
        GlStateManager._clearColor(0.0F, 0.0F, 0.0F, 0.0F);
        GlStateManager._clear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT, true);
        hudActive = true;
        GpuProfiler.begin(GpuProfiler.HUD);
    }

    /** Composites the downscaled HUD over the world in the real main target. */
    public static void endHudFrame(Minecraft minecraft) {
        if (!hudActive) {
            return;
        }
        hudActive = false;
        RenderTarget hud = hudTarget;
        RenderTarget main = minecraft.getMainRenderTarget();
        if (hud == null || main == null) {
            GpuProfiler.end(GpuProfiler.HUD);
            return;
        }
        main.bindWrite(true);
        GlStateManager._enableBlend();
        GlStateManager._blendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA,
                GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
        hud.blitToScreen(main.width, main.height, false);
        GlStateManager._disableBlend();
        // The composite blit is presentation work for the HUD and belongs in its section.
        GpuProfiler.end(GpuProfiler.HUD);
    }

    /** True while the whole frame (world + HUD) is being rendered into the scaled target. */
    public static boolean wholeWindowActive() {
        return wholeWindowActive;
    }

    /** The target the world renders into, or {@code null} while scaling is inactive. */
    public static RenderTarget scaledTarget() {
        return activeWorldTarget();
    }

    // ------------------------------------------------------------------
    // Shadow map hold (update every N frames)
    // ------------------------------------------------------------------

    private static double shadowHoldCarry;
    private static boolean shadowHoldHaveMap;
    private static int shadowHoldFramebuffer = -1;
    private static int shadowHoldDrawnFrames;
    private static int shadowHoldSkippedFrames;
    private static int shadowHoldForcedCamera;
    private static int shadowHoldForcedTime;
    private static int shadowHoldForcedCaster;
    private static int shadowHoldForceReasons;
    private static double shadowStatsInterval = -1.0;
    private static boolean shadowHoldFboWarned;
    private static boolean shadowClearWindow;
    private static boolean shadowClearWindowLogged;
    private static boolean shadowHoldSkipFrame;
    private static long shadowFrameToken;
    private static long shadowDecisionToken = -1L;
    private static boolean shadowProbe;
    private static long shadowHoldBlockX;
    private static long shadowHoldBlockY;
    private static long shadowHoldBlockZ;
    private static boolean shadowHoldForceDraw;
    private static long shadowHoldWorldTime = Long.MIN_VALUE;
    private static double shadowHoldCasterRadius = 128.0;
    private static long shadowHoldCasterSum;
    private static final java.util.Map<Integer, double[]> shadowHoldEntities = new java.util.HashMap<>();

    private static final int SHADOW_FORCE_CAMERA = 1;
    private static final int SHADOW_FORCE_TIME = 2;
    private static final int SHADOW_FORCE_CASTER = 4;

    /**
     * A living caster moving more than this invalidates the held map (blocks, squared). Mobs and
     * players are the casters whose frozen shadows are actually noticeable. Non-living casters
     * (boats, minecarts, ...) use the looser value below so ordinary physics does not force a
     * rebuild every frame.
     */
    private static final double SHADOW_HOLD_LIVING_MOVE_SQ = 0.01;
    private static final double SHADOW_HOLD_OTHER_MOVE_SQ = 0.25;
    /** A large sun/time jump forces a rebuild; normal daylight movement is handled by the interval. */
    private static final long SHADOW_HOLD_TIME_STEP = 40;

    /** Marks the Iris level-start window, where direct shadow-target clears are issued. */
    public static void beginShadowClearWindow() {
        // The frame-start camera check runs before GameRenderer#renderLevel installs the current
        // camera, so it sees the previous frame's position. Check again here, after the camera is
        // current and before the clear decision and the shadow pass are made. A block crossing
        // must force the draw on this frame; otherwise a held map is translated by more than one
        // block for a frame and the shadows visibly jump.
        shadowHoldCheckCameraNow();
        if (shadowDecisionToken != shadowFrameToken) {
            shadowDecisionToken = shadowFrameToken;
            decideShadowFrame();
        }
        shadowClearWindow = true;
        if (Boolean.getBoolean("modernopt.bench") && !shadowClearWindowLogged) {
            shadowClearWindowLogged = true;
            ModernOPT.LOG.info("[BENCH][SHADOWGL] level-start clear window active");
        }
    }

    /**
     * Fractional update scheduler. Each frame adds one to a carry and rebuilds when the carry
     * reaches the configured average interval; the surplus is kept for the next frame. An
     * interval of 1.5 therefore produces draw, draw, skip, draw, draw, skip. Integer intervals
     * keep their old every-N-frames cadence.
     */
    private static void decideShadowFrame() {
        double interval = Math.max(1.0, ModernOptSettings.shadowUpdateInterval());
        boolean forced = !shadowHoldHaveMap || shadowHoldForceDraw || shadowHoldFramebuffer <= 0
                || net.cr512.modernopt.compat.ShadowClearRegistry.holdDisabled()
                || interval <= 1.0;
        double next = shadowHoldCarry + 1.0;
        if (forced) {
            shadowHoldCarry = 0.0;
            shadowHoldSkipFrame = false;
        } else if (next + 1.0e-9 >= interval) {
            shadowHoldCarry = Math.max(0.0, next - interval);
            shadowHoldSkipFrame = false;
        } else {
            shadowHoldCarry = next;
            shadowHoldSkipFrame = true;
        }
    }

    private static void shadowHoldCheckCameraNow() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null) {
            return;
        }
        net.minecraft.client.Camera camera = minecraft.gameRenderer.getMainCamera();
        if (camera == null) {
            return;
        }
        net.minecraft.world.phys.Vec3 position = camera.getPosition();
        shadowHoldCheckCamera(position.x, position.y, position.z);
    }

    public static void endShadowClearWindow() {
        shadowClearWindow = false;
    }

    public static boolean inShadowClearWindow() {
        return shadowClearWindow;
    }

    /** Enables the shadow-depth readback used by the {@code !pairs} flicker diagnostic. */
    public static void setShadowProbe(boolean enabled) {
        shadowProbe = enabled;
    }

    public static boolean shadowProbeEnabled() {
        return shadowProbe;
    }

    /** Frame-global shadow decision, fixed before the clear passes and the shadow pass run. */
    public static boolean shadowHoldSkipFrame() {
        return shadowHoldSkipFrame;
    }

    /** Camera position of the last frame that actually drew the map. */
    public static void shadowHoldSetCamera(double x, double y, double z) {
        shadowHoldBlockX = (long) Math.floor(x);
        shadowHoldBlockY = (long) Math.floor(y);
        shadowHoldBlockZ = (long) Math.floor(z);
    }

    /**
     * Forces a rebuild when the camera leaves the integer block it occupied when the map was
     * drawn. This is the same conservative rule the held-map idea uses: a sub-block move can be
     * corrected by translating the light matrix, crossing a block boundary is a real change and
     * gets a fresh map. It also bounds the translation to less than one block.
     */
    public static void shadowHoldCheckCamera(double x, double y, double z) {
        if (!shadowHoldHaveMap || ModernOptSettings.shadowUpdateInterval() <= 1) {
            return;
        }
        long blockX = (long) Math.floor(x);
        long blockY = (long) Math.floor(y);
        long blockZ = (long) Math.floor(z);
        if (blockX != shadowHoldBlockX || blockY != shadowHoldBlockY
                || blockZ != shadowHoldBlockZ) {
            shadowHoldForceDraw = true;
            shadowHoldForceReasons |= SHADOW_FORCE_CAMERA;
            if (Boolean.getBoolean("modernopt.bench")) {
                ModernOPT.LOG.info("[BENCH][SHADOWGL] camera left the drawn block: "
                                + "({},{},{}) -> ({},{},{})",
                        shadowHoldBlockX, shadowHoldBlockY, shadowHoldBlockZ,
                        blockX, blockY, blockZ);
            }
        }
    }

    /** Any sun/time change since the drawn frame requires a fresh shadow map. */
    public static void shadowHoldCheckWorldTime(long worldTime) {
        if (!shadowHoldHaveMap || ModernOptSettings.shadowUpdateInterval() <= 1) {
            return;
        }
        if (shadowHoldWorldTime != Long.MIN_VALUE && worldTime != shadowHoldWorldTime) {
            long delta = Math.abs(worldTime - shadowHoldWorldTime);
            delta = Math.min(delta, 24000L - Math.min(delta, 24000L));
            if (delta > SHADOW_HOLD_TIME_STEP) {
                shadowHoldForceDraw = true;
                shadowHoldForceReasons |= SHADOW_FORCE_TIME;
            }
        }
    }

    public static void shadowHoldSetWorldTime(long worldTime) {
        shadowHoldWorldTime = worldTime;
    }

    /**
     * Shadow distance from the active pack, in blocks. Iris computes separate terrain and entity
     * culling distances; the entity one is what matters here because only entities are tracked
     * for caster motion. Falls back to 128 when the pack value cannot be read.
     */
    public static void shadowHoldSetCasterRadius(double radius) {
        if (Double.isFinite(radius) && radius >= 16.0) {
            shadowHoldCasterRadius = Math.min(radius, 512.0);
        }
    }

    /** Entities that do not meaningfully contribute to the shadow map and move constantly. */
    private static boolean shadowHoldCountsAsCaster(net.minecraft.world.entity.Entity entity) {
        return !(entity instanceof net.minecraft.world.entity.item.ItemEntity)
                && !(entity instanceof net.minecraft.world.entity.projectile.AbstractArrow)
                && !(entity instanceof net.minecraft.world.entity.ExperienceOrb);
    }

    /** Remembers the casters that were visible when the map was drawn. */
    public static void shadowHoldCaptureEntities(net.minecraft.client.multiplayer.ClientLevel level,
            double cameraX, double cameraY, double cameraZ) {
        shadowHoldEntities.clear();
        shadowHoldCasterSum = 0L;
        if (level == null || ModernOptSettings.shadowUpdateInterval() <= 1
                || net.cr512.modernopt.compat.ShadowClearRegistry.holdDisabled()) {
            return;
        }
        double radius = shadowHoldCasterRadius;
        double radiusSq = radius * radius;
        long sum = 0L;
        Minecraft minecraft = Minecraft.getInstance();
        try {
            for (net.minecraft.world.entity.Entity entity : level.entitiesForRendering()) {
                if (!entity.isAlive() || !shadowHoldCountsAsCaster(entity)) {
                    continue;
                }
                if (entity == minecraft.player && minecraft.options.getCameraType().isFirstPerson()) {
                    // The local player is not drawn into the shadow map in first person; tracking
                    // its walk would force a redraw on every step for no visual gain.
                    continue;
                }
                double dx = entity.getX() - cameraX;
                double dy = entity.getY() - cameraY;
                double dz = entity.getZ() - cameraZ;
                if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                    shadowHoldEntities.put(entity.getId(),
                            new double[]{entity.getX(), entity.getY(), entity.getZ()});
                    sum += entity.getId();
                }
            }
        } catch (Throwable ignored) {
            // A concurrent entity update only means less precision for one hold window.
        }
        shadowHoldCasterSum = sum;
    }

    /**
     * Detection order matters for a held map:
     * <ol>
     *   <li>a caster entering or leaving the tracked radius changes the signature;</li>
     *   <li>a living caster moving more than 0.1 blocks forces a redraw, because a frozen mob
     *       shadow is the most visible artefact of the held map;</li>
     *   <li>non-living casters (boats, minecarts, ...) use a looser 0.5-block threshold.</li>
     * </ol>
     */
    public static void shadowHoldCheckEntities(net.minecraft.client.multiplayer.ClientLevel level,
            double cameraX, double cameraY, double cameraZ) {
        if (level == null || ModernOptSettings.shadowUpdateInterval() <= 1
                || net.cr512.modernopt.compat.ShadowClearRegistry.holdDisabled()) {
            return;
        }
        double radius = shadowHoldCasterRadius;
        double radiusSq = radius * radius;
        long sum = 0L;
        int count = 0;
        Minecraft minecraft = Minecraft.getInstance();
        try {
            for (net.minecraft.world.entity.Entity entity : level.entitiesForRendering()) {
                if (!entity.isAlive() || !shadowHoldCountsAsCaster(entity)) {
                    continue;
                }
                if (entity == minecraft.player && minecraft.options.getCameraType().isFirstPerson()) {
                    continue;
                }
                double dx = entity.getX() - cameraX;
                double dy = entity.getY() - cameraY;
                double dz = entity.getZ() - cameraZ;
                if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                    sum += entity.getId();
                    count++;
                }
            }
        } catch (Throwable ignored) {
            return;
        }
        if (count != shadowHoldEntities.size() || sum != shadowHoldCasterSum) {
            shadowHoldForceDraw = true;
            shadowHoldForceReasons |= SHADOW_FORCE_CASTER;
            return;
        }
        for (java.util.Map.Entry<Integer, double[]> entry : shadowHoldEntities.entrySet()) {
            net.minecraft.world.entity.Entity entity = level.getEntity(entry.getKey());
            if (entity == null) {
                continue;
            }
            double[] position = entry.getValue();
            double dx = entity.getX() - position[0];
            double dy = entity.getY() - position[1];
            double dz = entity.getZ() - position[2];
            double limit = entity instanceof net.minecraft.world.entity.LivingEntity
                    ? SHADOW_HOLD_LIVING_MOVE_SQ : SHADOW_HOLD_OTHER_MOVE_SQ;
            if (dx * dx + dy * dy + dz * dz > limit) {
                shadowHoldForceDraw = true;
                shadowHoldForceReasons |= SHADOW_FORCE_CASTER;
                return;
            }
        }
    }

    /** Marks a frame that skipped the shadow pass. */
    public static void shadowHoldNoteSkipped() {
        shadowHoldSkippedFrames++;
    }

    /** Marks a frame that drew the shadow map. */
    public static void shadowHoldNoteDrawn() {
        shadowHoldHaveMap = true;
        shadowHoldForceDraw = false;
        shadowHoldDrawnFrames++;
        if (shadowHoldFramebuffer <= 0 && ModernOptSettings.shadowUpdateInterval() > 1
                && !shadowHoldFboWarned) {
            shadowHoldFboWarned = true;
            ModernOPT.LOG.warn("[ModernOPT] Iris shadow depth FBO id is unavailable; shadow-map "
                    + "hold is disabled for this session to prevent flicker");
        }
        if ((shadowHoldForceReasons & SHADOW_FORCE_CAMERA) != 0) {
            shadowHoldForcedCamera++;
        }
        if ((shadowHoldForceReasons & SHADOW_FORCE_TIME) != 0) {
            shadowHoldForcedTime++;
        }
        if ((shadowHoldForceReasons & SHADOW_FORCE_CASTER) != 0) {
            shadowHoldForcedCaster++;
        }
        shadowHoldForceReasons = 0;
    }

    /** One shared snapshot for the profiler, the overlay and any future UI. */
    public static ShadowHoldStats shadowHoldStats() {
        int drawn = shadowHoldDrawnFrames;
        int held = shadowHoldSkippedFrames;
        boolean disabled = net.cr512.modernopt.compat.ShadowClearRegistry.holdDisabled();
        double interval = disabled ? 1.0 : (shadowStatsInterval > 0.0
                ? shadowStatsInterval : ModernOptSettings.shadowUpdateInterval());
        return new ShadowHoldStats(drawn, held, drawn + held, interval,
                shadowHoldForcedCamera, shadowHoldForcedTime, shadowHoldForcedCaster);
    }

    /** Frames that actually redrew the shadow map since the last stats reset. */
    public static int shadowHoldDrawnFrames() {
        return shadowHoldDrawnFrames;
    }

    /** Frames that reused the held shadow map since the last stats reset. */
    public static int shadowHoldSkippedFrames() {
        return shadowHoldSkippedFrames;
    }

    /** Drawn + held frames, i.e. the denominator of the hold ratio. */
    public static int shadowHoldTotalFrames() {
        return shadowHoldDrawnFrames + shadowHoldSkippedFrames;
    }

    /** Fraction of frames that drew the shadow map (1.0 when no sample exists yet). */
    public static double shadowHoldDrawnShare() {
        int total = shadowHoldTotalFrames();
        return total <= 0 ? 1.0 : (double) shadowHoldDrawnFrames / total;
    }

    private static void shadowHoldResetStats() {
        shadowHoldDrawnFrames = 0;
        shadowHoldSkippedFrames = 0;
        shadowHoldForcedCamera = 0;
        shadowHoldForcedTime = 0;
        shadowHoldForcedCaster = 0;
        shadowHoldForceReasons = 0;
    }

    /**
     * Forgets the held map when Iris recreates its shadow targets (pack reload, resolution
     * change). The old framebuffer id and clear passes are no longer valid, so the next frame must
     * rebuild the map.
     */
    public static void shadowHoldResetTargets() {
        shadowHoldCarry = 0.0;
        shadowHoldHaveMap = false;
        shadowHoldForceDraw = false;
        shadowHoldFramebuffer = -1;
        shadowHoldWorldTime = Long.MIN_VALUE;
        shadowHoldCasterSum = 0L;
        shadowHoldResetStats();
        shadowHoldEntities.clear();
    }

    /** GL id of the shadow framebuffer the pack samples, or -1 when unknown. */
    public static int shadowHoldFramebuffer() {
        return shadowHoldFramebuffer;
    }

    public static void shadowHoldSetFramebuffer(int framebuffer) {
        shadowHoldFramebuffer = framebuffer;
    }

    /**
     * Re-draws the first-person hand at native resolution after the world has been upscaled.
     *
     * <p>Called from {@code GameRenderer#renderItemInHand} right where vanilla submits the hand,
     * i.e. with the hand projection, the bobbing pose and the light texture already set up and
     * with the main target bound and its depth buffer freshly cleared. Iris cancels vanilla's own
     * call when a shader pack is active, so this does not draw the hand twice.</p>
     */
    public static void renderDeferredHand(GameRenderer gameRenderer, Camera camera, float partialTick,
            PoseStack poseStack) {
        if (!deferredHandThisFrame) {
            return;
        }
        deferredHandThisFrame = false;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || camera.getEntity() == null) {
            return;
        }
        com.mojang.blaze3d.vertex.ByteBufferBuilder builder =
                new com.mojang.blaze3d.vertex.ByteBufferBuilder(8192);
        MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(builder);
        // Iris throws away every vanilla shader while a pack is active, and its hand mixin also
        // cancels the draw unless HandRenderer's solid flag matches the item type. Outside of
        // Iris' own hand pass that test rejects everything, so ModernOPT claims the hand pass
        // here and draws it exactly the way Iris does: solid items first, translucent items next.
        Boolean previousShaders = IrisCompat.setAllowUnknownShaders(true);
        Boolean previousSolid = IrisCompat.setRenderingHandSolid(true);
        try {
            int light = minecraft.getEntityRenderDispatcher().getPackedLightCoords(camera.getEntity(), partialTick);
            gameRenderer.itemInHandRenderer.renderHandsWithItems(partialTick, poseStack, buffers, player, light);
            buffers.endBatch();
            // Second pass: Iris' skip test now accepts only translucent items (glass, ice, ...).
            IrisCompat.setRenderingHandSolid(false);
            gameRenderer.itemInHandRenderer.renderHandsWithItems(partialTick, poseStack, buffers, player, light);
            buffers.endBatch();
        } catch (Throwable throwable) {
            ModernOPT.LOG.warn("[ModernOPT] deferred first-person hand failed", throwable);
        } finally {
            builder.close();
            if (previousSolid != null) {
                IrisCompat.setRenderingHandSolid(previousSolid);
            }
            if (previousShaders != null) {
                IrisCompat.setAllowUnknownShaders(previousShaders);
            }
        }
    }

    private static void finishWorldFrame() {
        RenderTarget world = frameWorldTarget;
        if (!worldActive || world == null) {
            return;
        }
        GpuProfiler.end(GpuProfiler.WORLD);
        GpuProfiler.endCpuWorld();
        GpuProfiler.begin(GpuProfiler.UPSCALE);
        if ((dbgFrameCounter % 240) == 0) {
            ModernOPT.LOG.info("[BENCH][GL] end {}", debugGlState());
        }

        boolean temporal = msrThisFrame && poseCaptured && !msrUnsupported;
        boolean jittered = poseCaptured;
        msrActiveThisFrame = temporal;

        if (wholeWindowActive) {
            // Nothing to reconstruct into the window target: the scaled target already holds the
            // whole frame and Minecraft#run's final blit upscales it. Keep the window size
            // override alive so the HUD lays out in the same GUI units as the world.
            worldActive = false;
            msrThisFrame = false;
            poseCaptured = false;
            msr2ThisFrame = false;
            restoreProjection(jittered);
            GpuProfiler.end(GpuProfiler.UPSCALE);
            return;
        }

        worldActive = false;
        windowScaled = false;
        // Put the real target back before reading it: reconstruction, the hand and the HUD all
        // have to see the native target, not the small world field.
        restoreMainTarget();
        msrThisFrame = false;
        poseCaptured = false;

        // Handle MSR2
        if (msr2ThisFrame) {
            msr2ActiveThisFrame = true;
            msr2ThisFrame = false;
            // MSR2 jitters the projection as well; `jittered` was captured from poseCaptured
            // above, so the hand and the GUI receive the unjittered matrix in the finally block.
        }

        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.getMainRenderTarget();
        if (!ensureShaders(minecraft)) {
            restoreProjection(jittered);
            main.bindWrite(true);
            frameWorldTarget = null;
            GpuProfiler.end(GpuProfiler.UPSCALE);
            return;
        }

        try {
            if (world == scaled && scaled != null) {
                scaled.resolveMsaa();
            }
            if (temporal) {
                try {
                    reconstructMsr(main);
                    msrFailureCount = 0;
                } catch (Throwable throwable) {
                    resetHistory();
                    msrActiveThisFrame = false;
                    msrFailureCount++;
                    if (msrFailureCount >= 3) {
                        msrUnsupported = true;
                    }
                    if (!loggedMsrFallback) {
                        loggedMsrFallback = true;
                        ModernOPT.LOG.error("[ModernOPT] MSR pass failed, falling back to FSR 1"
                                + (msrFailureCount >= 3 ? " for this session" : " for this frame"), throwable);
                    } else {
                        ModernOPT.LOG.error("[ModernOPT] MSR pass failed again (loggedMsrFallback already true): " + throwable.getMessage());
                    }
                    reconstructSpatial(main, world);
                }
            } else if (msr2ActiveThisFrame) {
                try {
                    reconstructMsr2(main);
                    msrFailureCount = 0; // reuse: reset failure counter on success
                } catch (Throwable throwable) {
                    msrShaderFailure = true;
                    // Never leave the MSR history FBO / compute program / image units bound:
                    // the FSR 1 fallback below must draw into the real main target.
                    releaseMsrState(main);
                    resetMsr2History();
                    ModernOPT.LOG.error("[ModernOPT] MSR2 pass failed, falling back to FSR 1", throwable);
                    try {
                        reconstructSpatial(main, world);
                    } catch (Throwable fallbackFailure) {
                        ModernOPT.LOG.error("[ModernOPT] FSR 1 fallback after MSR failure also failed", fallbackFailure);
                    }
                }
                msr2ActiveThisFrame = false;
            } else {
                reconstructSpatial(main, world);
            }
        } catch (Throwable throwable) {
            ModernOPT.LOG.error("[ModernOPT] reconstruction failed, falling back to a plain blit", throwable);
            shaderFailure = true;
        } finally {
            restoreProjection(jittered);
            main.bindWrite(true);
            // The real main target is only used for the final composite now, so vanilla never
            // clears its depth buffer (its frame-start clear goes to the scaled target). A stale
            // all-zero depth buffer then fails the hand and HUD depth tests: the first-person
            // hand and the entire HUD simply do not appear. Clear it here, after the last
            // reconstruction pass and before the hand/GUI passes run.
            GlStateManager._depthMask(true);
            org.lwjgl.opengl.GL11C.glClearDepth(1.0D);
            org.lwjgl.opengl.GL11C.glClear(org.lwjgl.opengl.GL11C.GL_DEPTH_BUFFER_BIT);
            // Iris cancels vanilla's hand pass while a pack is active and draws it inside the
            // world pipeline instead. That copy never survives the composite at a reduced render
            // scale, so the hand is redrawn natively right after this frame is upscaled.
            deferredHandThisFrame = irisThisFrame;
            frameWorldTarget = null;
            GpuProfiler.end(GpuProfiler.UPSCALE);
        }
    }

    private static void restoreProjection(boolean jittered) {
        if (jittered) {
            RenderSystem.setProjectionMatrix(currentProjection, VertexSorting.DISTANCE_TO_ORIGIN);
        }
    }

    /**
     * MSR chain: EASU to output resolution, temporal accumulation against the reprojected
     * history, then RCAS (and optionally FXAA). Runs in the output resolution for the whole
     * temporal stage, so the history is not limited by the internal resolution.
     */
    private static void reconstructMsr(RenderTarget main) {
        ShaderLibrary library = shaders;
        if (library == null || library.temporal == null) {
            throw new IllegalStateException("temporal shader is not available");
        }
        HalfFloatTarget read = historyFront;
        HalfFloatTarget write = historyBack;
        if (read == null || write == null) {
            throw new IllegalStateException("history targets are not allocated");
        }

        int outWidth = main.width;
        int outHeight = main.height;
        int sourceWidth = scaled.width;
        int sourceHeight = scaled.height;
        int sourceTexture = scaled.getColorTextureId();
        int depthTexture = scaled.getDepthTextureId();
        boolean depthValid = true;
        if (irisThisFrame) {
            int irisDepth = IrisCompat.depthTextureId();
            if (irisDepth > 0) {
                depthTexture = irisDepth;
                irisDepthMisses = 0;
            } else {
                depthValid = false;
                irisDepthMisses++;
                if (irisDepthMisses >= 60 && !loggedIrisDepth) {
                    loggedIrisDepth = true;
                    ModernOPT.LOG.warn("[ModernOPT] Iris depth texture is unavailable after 60 frames; "
                            + "MSR reprojection is rotation-only (disocclusion test disabled)");
                }
            }
        } else {
            irisDepthMisses = 0;
        }
        msrDepthValidThisFrame = depthValid;

        currentProjection.invert(inverseProjection);
        if (!inverseProjection.isFinite()) {
            throw new IllegalStateException("projection matrix is not invertible");
        }
        
        // Cache the inverse for potential reuse (only recompute when projection changes)
        if (cachedProjection == null || !currentProjection.equals(cachedProjection)) {
            cachedProjection = new Matrix4f(currentProjection);
            cachedInverseProjection = new Matrix4f(inverseProjection);
        }

        boolean useHistory = historyValid;
        tempVec3.set((float) (currentCameraX - previousCameraX),
                (float) (currentCameraY - previousCameraY),
                (float) (currentCameraZ - previousCameraZ));
        if (tempVec3.x * tempVec3.x + tempVec3.y * tempVec3.y + tempVec3.z * tempVec3.z > 64.0 * 64.0) {
            // Teleport or dimension change: do not smear the old image across the new position.
            useHistory = false;
        }

        TextureTarget easu = ensureIntermediates(outWidth, outHeight, true);
        float rcasSharpness = cachedFsrRcasSharpness / 100.0F;
        boolean fxaa = cachedFxaa;
        boolean useLanczos = ModernOptSettings.msr2Lanczos() && library.hasLanczos();
        ShaderInstance primary = useLanczos ? library.lanczos : library.easu;
        float primaryParam = useLanczos ? (ModernOptSettings.msr2Lanczos3() ? 3.0F : 2.0F) : 0.0F;
        // MSR's temporal pass applies the jitter offset itself when it samples the EASU output.
        setUniform2f(library.easu, "Jitter", 0.0F, 0.0F);

        ShaderLibrary.beginPassState();
        try {
            easu.bindWrite(true);
            ShaderLibrary.draw(primary, sourceTexture, sourceWidth, sourceHeight,
                    outWidth, outHeight, primaryParam, 0.0F);

            write.bindWrite(true);
            ShaderLibrary.drawTemporal(library.temporal,
                    easu.getColorTextureId(), read.getColorTextureId(), depthTexture,
                    sourceWidth, sourceHeight, outWidth, outHeight,
                    inverseProjection, previousProjection, currentView, previousView,
                    tempVec3.x, tempVec3.y, tempVec3.z,
                    jitterX, jitterY,
                    useHistory, depthValid, irisThisFrame,
                    0.10F, 0.60F, 0.05F);

            // The write target is now the newest history; ping-pong them for the next frame.
            historyFront = write;
            historyBack = read;

            if (fxaa) {
                TextureTarget post = ensureIntermediates(outWidth, outHeight, false);
                post.bindWrite(true);
                ShaderLibrary.draw(library.rcas, write.getColorTextureId(), outWidth, outHeight,
                        outWidth, outHeight, rcasSharpness, 0.0F);
                main.bindWrite(true);
                ShaderLibrary.draw(library.fxaa, post.getColorTextureId(), outWidth, outHeight,
                        outWidth, outHeight, 0.0F, 0.0F);
            } else {
                main.bindWrite(true);
                ShaderLibrary.draw(library.rcas, write.getColorTextureId(), outWidth, outHeight,
                        outWidth, outHeight, rcasSharpness, 0.0F);
            }
        } finally {
            ShaderLibrary.endPassState();
            main.bindWrite(true);
        }

        historyValid = true;
        previousProjection.set(currentProjection);
        previousView.set(currentView);
        previousCameraX = currentCameraX;
        previousCameraY = currentCameraY;
        previousCameraZ = currentCameraZ;

        if (outWidth != lastWidth || outHeight != lastHeight) {
            lastWidth = outWidth;
            lastHeight = outHeight;
            ModernOPT.LOG.info("[ModernOPT] presenting {}x{} world image at {}x{} (MSR)",
                    sourceWidth, sourceHeight, outWidth, outHeight);
        }
    }

    /** Spatial reconstruction used by every non-temporal mode, and by the MSR fallback. */
    private static void reconstructSpatial(RenderTarget main, RenderTarget source) {
        if (source == null) {
            throw new IllegalStateException("world target is not allocated");
        }
        int outWidth = main.width;
        int outHeight = main.height;
        int sourceTexture = source.getColorTextureId();
        int sourceWidth = source.width;
        int sourceHeight = source.height;

        float softness = cachedSoftness / 100.0F;
        float rcasSharpness = cachedFsrRcasSharpness / 100.0F;
        boolean fxaa = cachedFxaa;
        ShaderLibrary library = shaders;
        UpscalerType upscaler = cachedUpscaler;
        // FSR 1 / MSR render an unjittered frame; only the MSR2 temporal prepass de-jitters EASU.
        setUniform2f(library.easu, "Jitter", 0.0F, 0.0F);
        boolean lanczosAvailable = library != null && library.hasLanczos();
        boolean useLanczos = (upscaler == UpscalerType.FSR1 || upscaler == UpscalerType.MSR)
                && ModernOptSettings.msr2Lanczos() && lanczosAvailable;
        float lanczosRadius = ModernOptSettings.msr2Lanczos3() ? 3.0F : 2.0F;
        boolean fsr1Path = !cachedSsaa
                && (upscaler == UpscalerType.FSR1 || upscaler == UpscalerType.MSR);

        // At small upscale factors (~1.25x), EASU is slower than bilinear - use simpler filter
        float upscale = (float) outWidth / (float) sourceWidth;
        boolean simpleUpscale = upscale < 1.11F && fsr1Path && !useLanczos;
        ShaderInstance fsrPrimary = useLanczos ? library.lanczos : library.easu;
        float fsrPrimaryParam = useLanczos ? lanczosRadius : 0.0F;

        ShaderLibrary.beginPassState();
        try {
            if (fsr1Path) {
                if (simpleUpscale) {
                    // Simple bilinear + optional RCAS for small upscales
                    ShaderInstance filter = cachedSsaa ? library.box : filterFor(library, upscaler);
                    if (fxaa) {
                        TextureTarget post = ensureIntermediates(outWidth, outHeight, false);
                        post.bindWrite(true);
                        ShaderLibrary.draw(filter, sourceTexture, sourceWidth, sourceHeight, outWidth, outHeight, softness, 0.0F);
                        main.bindWrite(true);
                        ShaderLibrary.draw(library.fxaa, post.getColorTextureId(), outWidth, outHeight, outWidth, outHeight, 0.0F, 0.0F);
                    } else {
                        main.bindWrite(true);
                        ShaderLibrary.draw(filter, sourceTexture, sourceWidth, sourceHeight, outWidth, outHeight, softness, 0.0F);
                    }
                } else if (!fxaa && rcasSharpness <= 0.0F) {
                    // No sharpening requested: EASU can write straight into the real main target.
                    // The intermediate full-resolution buffer and the RCAS pass exist only to
                    // sharpen, so running them with a zero lobe was pure overhead.
                    main.bindWrite(true);
                    ShaderLibrary.draw(fsrPrimary, sourceTexture, sourceWidth, sourceHeight,
                            outWidth, outHeight, fsrPrimaryParam, 0.0F);
                } else {
                    // Full FSR 1 EASU (or generic Lanczos) + RCAS path
                    TextureTarget easu = ensureIntermediates(outWidth, outHeight, true);
                    easu.bindWrite(true);
                    ShaderLibrary.draw(fsrPrimary, sourceTexture, sourceWidth, sourceHeight, outWidth, outHeight, fsrPrimaryParam, 0.0F);

                    if (fxaa) {
                        TextureTarget post = ensureIntermediates(outWidth, outHeight, false);
                        post.bindWrite(true);
                        ShaderLibrary.draw(library.rcas, easu.getColorTextureId(), outWidth, outHeight, outWidth, outHeight, rcasSharpness, 0.0F);
                        main.bindWrite(true);
                        ShaderLibrary.draw(library.fxaa, post.getColorTextureId(), outWidth, outHeight, outWidth, outHeight, 0.0F, 0.0F);
                    } else {
                        main.bindWrite(true);
                        ShaderLibrary.draw(library.rcas, easu.getColorTextureId(), outWidth, outHeight, outWidth, outHeight, rcasSharpness, 0.0F);
                    }
                }
            } else {
                ShaderInstance filter = cachedSsaa ? library.box : filterFor(library, upscaler);
                float filterParam = softness;
                if (fxaa) {
                    TextureTarget post = ensureIntermediates(outWidth, outHeight, false);
                    post.bindWrite(true);
                    ShaderLibrary.draw(filter, sourceTexture, sourceWidth, sourceHeight, outWidth, outHeight, filterParam, 0.0F);
                    main.bindWrite(true);
                    ShaderLibrary.draw(library.fxaa, post.getColorTextureId(), outWidth, outHeight, outWidth, outHeight, 0.0F, 0.0F);
                } else {
                    main.bindWrite(true);
                    ShaderLibrary.draw(filter, sourceTexture, sourceWidth, sourceHeight, outWidth, outHeight, filterParam, 0.0F);
                }
            }
        } finally {
            ShaderLibrary.endPassState();
            main.bindWrite(true);
        }

        if (outWidth != lastWidth || outHeight != lastHeight) {
            lastWidth = outWidth;
            lastHeight = outHeight;
            int samples = source == scaled && scaled != null ? scaled.activeSamples() : 1;
            ModernOPT.LOG.info("[ModernOPT] presenting {}x{} world image at {}x{} (samples={})",
                    sourceWidth, sourceHeight, outWidth, outHeight, samples);
        }
    }

    private static ShaderInstance filterFor(ShaderLibrary library, UpscalerType type) {
        return switch (type) {
            case NEAREST -> library.nearest;
            case SOFT -> library.soft;
            case BILINEAR, FSR1, MSR, MSR2 -> library.bilinear;
        };
    }

    private static TextureTarget ensureIntermediates(int width, int height, boolean easuStage) {
        if (easuStage) {
            if (fsrTarget == null || fsrTarget.width != width || fsrTarget.height != height) {
                if (fsrTarget != null) {
                    fsrTarget.destroyBuffers();
                }
                fsrTarget = new TextureTarget(width, height, false, false);
            }
            return fsrTarget;
        }

        if (fxaaTarget == null || fxaaTarget.width != width || fxaaTarget.height != height) {
            if (fxaaTarget != null) {
                fxaaTarget.destroyBuffers();
            }
            fxaaTarget = new TextureTarget(width, height, false, false);
        }
        return fxaaTarget;
    }

    private static void ensureHistoryTargets(int width, int height) {
        if (historyA != null && historyB != null
                && historyA.width == width && historyA.height == height
                && historyB.width == width && historyB.height == height) {
            return;
        }
        destroyHistoryTargets();
        historyA = new HalfFloatTarget(width, height);
        historyB = new HalfFloatTarget(width, height);
        historyFront = historyA;
        historyBack = historyB;
        historyValid = false;
        jitterIndex = 0;
        ModernOPT.LOG.info("[ModernOPT] allocated MSR history targets at {}x{}", width, height);
    }

    private static void destroyHistoryTargets() {
        if (historyA != null) {
            historyA.destroyBuffers();
            historyA = null;
        }
        if (historyB != null) {
            historyB.destroyBuffers();
            historyB = null;
        }
        historyFront = null;
        historyBack = null;
    }

    private static void resetHistory() {
        historyValid = false;
        poseCaptured = false;
        jitterIndex = 0;
    }

    /** Drops every temporal history so the next frame starts from an unaccumulated image. */
    public static void resetTemporalHistory() {
        resetHistory();
        resetMsr2History();
    }

    private static void rememberUpscalerSettings(UpscalerType requested) {
        lastUpscaler = requested;
        lastUpscalerPercent = ModernOptSettings.renderScalePercent();
        lastUpscalerSsaa = ModernOptSettings.ssaa();
    }

    private static boolean isUsableProjection(Matrix4f projection) {
        if (!projection.isFinite()) {
            return false;
        }
        float determinant = projection.determinant();
        return Float.isFinite(determinant) && Math.abs(determinant) > 1.0E-8F;
    }

    private static float halton(int index, int base) {
        float result = 0.0F;
        float fraction = 1.0F;
        int value = index;
        while (value > 0) {
            fraction /= base;
            result += fraction * (value % base);
            value /= base;
        }
        return result;
    }

    /**
     * Whole-window mode: the frame renders straight into the real main target, resized to the
     * internal resolution, and is presented to the window by {@link #endWholeWindowFrame()}.
     */
    private static void beginWholeWindowFrame(Minecraft minecraft) {
        int windowWidth = minecraft.getWindow().getWidth();
        int windowHeight = minecraft.getWindow().getHeight();
        if (windowWidth <= 0 || windowHeight <= 0) {
            return;
        }
        RenderTarget main = minecraft.getMainRenderTarget();
        if (main == null) {
            return;
        }
        int effectiveScale = effectiveRenderScale();
        logMovingResolution(effectiveScale);
        int[] size = RenderScaleMath.targetSize(windowWidth, windowHeight, effectiveScale,
                cachedSsaa, RenderSystem.maxSupportedTextureSize());
        if (size[0] >= windowWidth || size[1] >= windowHeight) {
            restoreWindowSizedMainTarget(minecraft);
            return;
        }
        if (main.width != size[0] || main.height != size[1]) {
            main.resize(size[0], size[1], Minecraft.ON_OSX);
            resetHistory();
            ModernOPT.LOG.info("[ModernOPT] whole-window scaling: main target {}x{} -> {}x{}",
                    windowWidth, windowHeight, size[0], size[1]);
        }
        main.setFilterMode(org.lwjgl.opengl.GL11C.GL_LINEAR);
        realWindowWidth = windowWidth;
        realWindowHeight = windowHeight;
        windowScaled = true;
        wholeWindowActive = true;
        // The main target is smaller than the window. Bind it with setViewport=true here: when a
        // GUI screen is open, vanilla does not rebind it before drawing the world behind the menu,
        // leaving a window-sized viewport on a small framebuffer and cropping the world to a corner.
        main.bindWrite(true);
        // Iris cancels vanilla's hand pass; draw it ourselves like in the scaled mode.
        deferredHandThisFrame = true;
        resetHistory();
    }

    /** Puts the main target back to the real window size when whole-window scaling is off. */
    private static void restoreWindowSizedMainTarget(Minecraft minecraft) {
        restoreMainTarget();
        boolean wasWholeWindow = wholeWindowActive;
        RenderTarget main = minecraft.getMainRenderTarget();
        int windowWidth = minecraft.getWindow().getWidth();
        int windowHeight = minecraft.getWindow().getHeight();
        if (main != null && windowWidth > 0 && windowHeight > 0
                && (main.width != windowWidth || main.height != windowHeight)) {
            main.resize(windowWidth, windowHeight, Minecraft.ON_OSX);
            main.setFilterMode(org.lwjgl.opengl.GL11C.GL_NEAREST);
            ModernOPT.LOG.info("[ModernOPT] restored the main target to {}x{}",
                    windowWidth, windowHeight);
        } else if (main != null && wasWholeWindow) {
            // Same size but the filter was switched to LINEAR for whole-window presentation.
            main.setFilterMode(org.lwjgl.opengl.GL11C.GL_NEAREST);
        }
        wholeWindowActive = false;
        windowScaled = false;
    }

    /**
     * Ends a whole-window frame and presents the complete small frame into the real window just
     * before the back buffer is swapped. Using our own blit keeps the result independent of the
     * window-size override and of vanilla's final blit.
     */
    public static void endWholeWindowFrame() {
        Minecraft minecraft = Minecraft.getInstance();
        // Drop the overrides first so the window reports its real framebuffer size, even if it was
        // resized while a small main target was active.
        restoreMainTarget();
        frameWorldTarget = null;
        wholeWindowActive = false;
        windowScaled = false;
        int windowWidth = minecraft.getWindow().getWidth();
        int windowHeight = minecraft.getWindow().getHeight();
        RenderTarget main = minecraft.getMainRenderTarget();
        boolean smallMain = main != null && windowWidth > 0 && windowHeight > 0
                && (main.width != windowWidth || main.height != windowHeight);
        // A frame can end without the world pass having run (loading screen, menu, a mod cancelling
        // renderLevel). The main target is still small from whole-window mode then, so present it
        // anyway and always drop the window override - otherwise the GUI is laid out for the wrong
        // resolution and only a corner of the frame reaches the window.
        if (!smallMain) {
            return;
        }
        realWindowWidth = windowWidth;
        realWindowHeight = windowHeight;
        main.unbindWrite();
        org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER, 0);
        // Raw GL here: RenderTarget#blitToScreen's GlStateManager viewport call is intercepted
        // by other mods and can end up leaving the small size behind, which presents the frame
        // into a corner of the window with the rest black.
        org.lwjgl.opengl.GL11C.glViewport(0, 0, realWindowWidth, realWindowHeight);
        org.lwjgl.opengl.GL11C.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        org.lwjgl.opengl.GL11C.glClear(org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT
                | org.lwjgl.opengl.GL11C.GL_DEPTH_BUFFER_BIT);
        if (ensureShaders(minecraft) && shaders != null && shaders.isValid()) {
            ShaderLibrary.beginPassState();
            ShaderLibrary.drawBlit(shaders.blit, main.getColorTextureId(), main.width, main.height,
                    realWindowWidth, realWindowHeight);
            ShaderLibrary.endPassState();
        } else {
            main.blitToScreen(realWindowWidth, realWindowHeight, true);
        }
    }

    /** Points the viewport at the small main target before the GUI pass in whole-window mode. */
    public static void beginHudPass(Minecraft minecraft) {
        if (!wholeWindowActive) {
            return;
        }
        RenderTarget main = minecraft.getMainRenderTarget();
        if (main != null && main.width > 0 && main.height > 0) {
            GlStateManager._viewport(0, 0, main.width, main.height);
        }
    }

    private static void ensureFastTarget(int width, int height) {
        if (fastTarget != null && fastTarget.width == width && fastTarget.height == height) {
            return;
        }
        if (fastTarget != null) {
            fastTarget.destroyBuffers();
            fastTarget = null;
        }
        try {
            fastTarget = new FastWorldTarget(width, height);
            ModernOPT.LOG.info("[ModernOPT] fast world target {}x{} (single colour+depth, no MSAA/FP16)",
                    width, height);
        } catch (Throwable throwable) {
            ModernOPT.LOG.warn("[ModernOPT] cannot allocate the {}x{} fast target, using the advanced target",
                    width, height, throwable);
            fastTarget = null;
        }
    }

    /** Installs the world target in the real {@code Minecraft.mainRenderTarget} field. */
    private static void swapMainTarget(Minecraft minecraft, RenderTarget target) {        if (worldTargetSwapped || minecraft == null || target == null) {
            return;
        }
        net.cr512.modernopt.mixin.MinecraftAccessor accessor =
                (net.cr512.modernopt.mixin.MinecraftAccessor) minecraft;
        RenderTarget current = accessor.modernopt$getMainRenderTargetField();
        if (current == target) {
            return;
        }
        nativeMainTarget = current;
        accessor.modernopt$setMainRenderTarget(target);
        worldTargetSwapped = true;
    }

    /**
     * Returns the last completed native frame when a screenshot is requested during the world
     * pass. In fast mode the {@code mainRenderTarget} field points at the small world target for
     * the duration of that pass, and in advanced mode the getter redirects to it, so a naive
     * screenshot would capture a half-rendered, low-resolution image.
     */
    public static RenderTarget completedFrameTarget(RenderTarget requested) {
        if (!worldActive || requested == null || frameWorldTarget == null) {
            return requested;
        }
        if (requested != frameWorldTarget && requested != scaled) {
            return requested;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return requested;
        }
        RenderTarget real = nativeMainTarget;
        if (real == null) {
            real = ((net.cr512.modernopt.mixin.MinecraftAccessor) minecraft)
                    .modernopt$getMainRenderTargetField();
        }
        return real != null ? real : requested;
    }

    /** Restores the real {@code mainRenderTarget} captured by {@link #swapMainTarget}. */
    private static void restoreMainTarget() {        if (!worldTargetSwapped) {
            return;
        }
        worldTargetSwapped = false;
        RenderTarget nativeTarget = nativeMainTarget;
        nativeMainTarget = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (nativeTarget != null && minecraft != null) {
            ((net.cr512.modernopt.mixin.MinecraftAccessor) minecraft)
                    .modernopt$setMainRenderTarget(nativeTarget);
        }
    }

    private static void ensureScaledTarget(int width, int height, int samples) {
        boolean fp16 = ModernOptSettings.fp16World();
        if (scaled != null && scaled.width == width && scaled.height == height
                && scaled.requestedSamples() == Math.max(1, samples)
                && scaled.isMultisampled() == (samples > 1) && scaled.isFp16() == fp16) {
            return;
        }
        if (scaled != null) {
            scaled.destroyBuffers();
            scaled = null;
        }
        resetHistory();
        try {
            scaled = new ScaledRenderTarget(width, height, samples, fp16);
        } catch (Throwable throwable) {
            ModernOPT.LOG.error("[ModernOPT] cannot allocate a {}x{} render target, render scaling disabled until restart",
                    width, height, throwable);
            scaled = null;
        }
    }

    /**
     * Closes the cached shader programs so the next frame rebuilds them from the current resource
     * tree. Called when the options screen closes or the config is reloaded; the targets and
     * history are left alone, and the rebuild is lazy.
     */
    public static void reloadShaders() {
        if (shaders != null) {
            try {
                shaders.close();
            } catch (Throwable throwable) {
                ModernOPT.LOG.warn("[ModernOPT] could not close the shader library for reload", throwable);
            }
            shaders = null;
        }
        destroyMsrCompute();
        msrComputeFailed = false;
        shaderFailure = false;
        msrShaderFailure = false;
        // A changed reconstruction kernel must not be blended with history produced by the old
        // one; both temporal histories restart on the next frame.
        resetHistory();
        resetMsr2History();
    }

    private static boolean ensureShaders(Minecraft minecraft) {
        if (shaderFailure) {
            return false;
        }
        if (shaders != null && shaders.isValid()) {
            return true;
        }
        if (shaders != null) {
            try {
                shaders.close();
            } catch (Throwable ignored) {
                // vanilla already closed the programs during a resource reload
            }
            shaders = null;
        }
        destroyMsrCompute();

        if (!(minecraft.getResourceManager() instanceof ResourceProvider resources)) {
            shaderFailure = true;
            ModernOPT.LOG.error("[ModernOPT] resource manager is not a ResourceProvider, render scaling disabled");
            return false;
        }

        try {
            shaders = ShaderLibrary.load(resources);
            loadMsrCompute(resources);
            resetHistory();
            ModernOPT.LOG.info("[ModernOPT] upscaler shaders compiled");
            return true;
        } catch (Throwable throwable) {
            shaderFailure = true;
            ModernOPT.LOG.error("[ModernOPT] upscaler shaders failed to compile, render scaling disabled", throwable);
            return false;
        }
    }

    /**
     * Compiles the GL 4.3 compute temporal program. Failure is not fatal: the fragment
     * temporal shader stays available as a fallback (and that fallback is also used when the
     * driver reports GL < 4.3).
     */
    private static void loadMsrCompute(ResourceProvider resources) {
        if (motionVectors != null) {
            motionVectors.destroy();
            motionVectors = null;
        }
        if (!computeAvailable) {
            msrComputeProgram = null;
            msrComputeFailed = false;
            return;
        }
        msrComputeFailed = false;
        try {
            msrComputeProgram = Msr2TemporalCompute.load(resources);
            ModernOPT.LOG.info("[ModernOPT] MSR: GL 4.3 compute temporal shader compiled");
        } catch (Throwable throwable) {
            msrComputeProgram = null;
            msrComputeFailed = true;
            ModernOPT.LOG.warn("[ModernOPT] MSR: compute temporal unavailable, using the fragment fallback",
                    throwable);
            return;
        }
        try {
            motionVectors = new MotionVectorRenderer();
            ModernOPT.LOG.info("[ModernOPT] MSR2: entity motion-vector pass compiled");
        } catch (Throwable throwable) {
            motionVectors = null;
            ModernOPT.LOG.warn("[ModernOPT] MSR2: entity motion-vector pass unavailable; "
                    + "using camera reprojection", throwable);
        }
    }

    private static void destroyMsrCompute() {
        if (motionVectors != null) {
            try {
                motionVectors.destroy();
            } catch (Throwable ignored) {
                // context may already be gone during shutdown
            }
            motionVectors = null;
        }
        if (msrComputeProgram != null) {
            try {
                msrComputeProgram.delete();
            } catch (Throwable ignored) {
                // context may already be gone during shutdown
            }
            msrComputeProgram = null;
        }
    }

    private static boolean isFabulous(Minecraft minecraft) {
        try {
            return "FABULOUS".equals(minecraft.options.graphicsMode().get().name());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void logIrisOnce(String what) {
        if (!loggedIrisSkip) {
            loggedIrisSkip = true;
            ModernOPT.LOG.info("[ModernOPT] Iris: {}", what);
        }
    }

    /**
     * Bench-only diagnostic: one-line snapshot of the GL viewport and the framebuffers involved in
     * the scaled path. Remove once the scaled-path slowdown is understood.
     */
    public static String debugGlState() {
        int drawFbo = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] vp = new int[4];
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, vp);
        Minecraft minecraft = Minecraft.getInstance();
        String main = "?";
        String window = "?";
        if (minecraft != null) {
            main = minecraft.getMainRenderTarget().width + "x" + minecraft.getMainRenderTarget().height;
            if (minecraft.getWindow() != null) {
                window = minecraft.getWindow().getWidth() + "x" + minecraft.getWindow().getHeight();
            }
        }
        RenderTarget active = activeWorldTarget();
        return "window=" + window + " realWindow=" + realWindowWidth + "x" + realWindowHeight
                + " override=" + windowWidthOverride() + "x" + windowHeightOverride()
                + " main=" + main
                + " active=" + (active == null ? "null" : active.width + "x" + active.height)
                + " fast=" + fastWorldActive
                + " swapped=" + worldTargetSwapped
                + " drawFbo=" + drawFbo
                + " viewport=" + vp[0] + "," + vp[1] + " " + vp[2] + "x" + vp[3]
                + " worldActive=" + worldActive;
    }


    /**
     * Keeps the GL viewport matched to the scaled target.
     *
     * <p>Vanilla passes occasionally bind a window sized render target in the middle of the world
     * pass and then return with {@code bindWrite(false)}, which deliberately leaves the viewport
     * untouched. The terrain still looks right because it was already drawn, but every later pass
     * (entities, hand, particles) is then rasterised for a target twice as large and is clipped
     * away outside a corner. Forcing the size back whenever the scaled target is bound fixes that
     * without touching any other framebuffer (Iris gbuffers, shadow maps, post chains).</p>
     *
     * <p>Only a viewport that matches the window size is stale. Other sizes belong to the pass
     * that is setting them up: Iris sets the shadow map viewport (for example 2048x2048) before
     * it binds the shadow framebuffer, while the scaled world target is still current. Clamping
     * that request to the scaled size made the shadow map render into a small corner and removed
     * the shadows at every scale below 100%.</p>
     *
     * @return {@code true} when this call already applied the corrected viewport
     */
    public static boolean clampViewport(int x, int y, int width, int height) {
        if (wholeWindowActive) {
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            if (main == null || (width == main.width && height == main.height)) {
                return false;
            }
            if (width != realWindowWidth || height != realWindowHeight) {
                return false;
            }
            int current = org.lwjgl.opengl.GL11C.glGetInteger(
                    org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            if (current != main.frameBufferId) {
                return false;
            }
            GlStateManager._viewport(0, 0, main.width, main.height);
            return true;
        }
        RenderTarget target = activeWorldTarget();
        if (!worldActive || target == null) {
            return false;
        }
        if (width == target.viewWidth && height == target.viewHeight) {
            return false;
        }
        net.cr512.modernopt.render.WorldCpuProfiler.noteClampGlQuery();
        if (!isCurrentDrawTarget(target)) {
            return false;
        }

        if (width != realWindowWidth || height != realWindowHeight) {
            return false;
        }

        GlStateManager._viewport(0, 0, target.viewWidth, target.viewHeight);
        return true;
    }

    /** True when {@code target} (or its multisampled attachment) is the current draw buffer. */
    private static boolean isCurrentDrawTarget(RenderTarget target) {
        if (target == null || target.frameBufferId < 0) {
            return false;
        }
        int bound = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (bound == target.frameBufferId) {
            return true;
        }
        return target instanceof ScaledRenderTarget advanced
                && bound == advanced.activeFramebufferId();
    }

    /**
     * Repairs a window-sized viewport that leaked onto the world target through a
     * {@code bindWrite(false)}. Called from the target's own bind override, so the FBO is known to
     * be current and no GL framebuffer query is needed.
     */
    public static void repairWorldViewport(RenderTarget target, boolean setViewport) {
        if (setViewport || target == null) {
            return;
        }
        if (ScaledRenderTarget.viewportWidth() == target.viewWidth
                && ScaledRenderTarget.viewportHeight() == target.viewHeight) {
            return;
        }
        if (realWindowWidth <= 0 || realWindowHeight <= 0) {
            return;
        }
        if (ScaledRenderTarget.viewportWidth() != realWindowWidth
                || ScaledRenderTarget.viewportHeight() != realWindowHeight) {
            return;
        }
        GlStateManager._viewport(0, 0, target.viewWidth, target.viewHeight);
    }

    /** True when {@code framebuffer} belongs to {@code target}, including an MSAA attachment. */
    private static boolean isWorldFramebuffer(RenderTarget target, int framebuffer) {
        if (target == null) {
            return false;
        }
        if (framebuffer == target.frameBufferId) {
            return true;
        }
        return target instanceof ScaledRenderTarget advanced
                && framebuffer == advanced.activeFramebufferId();
    }

    /**
     * Called right after a framebuffer binding. When the scaled world target becomes the draw
     * buffer while OpenGL still carries a window sized viewport (left behind by a full resolution
     * helper target that was bound with {@code setViewport = false} afterwards), the viewport is
     * corrected to the scaled size.
     *
     * <p>Without this, entities, clouds and the sky are rasterised as if the framebuffer were the
     * full window: their screen centre maps to the target's corner, which is exactly the giant
     * player sliver in the top-right corner and the floating entity fragments in the sky.</p>
     */
    public static void onFramebufferBound(int target, int framebuffer) {
        RenderTarget activeTarget = activeWorldTarget();
        if (shadowPassActive()) {
            return;
        }
        if (!worldActive && !wholeWindowActive) {
            return;
        }
        if (target != org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER
                && target != org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER) {
            return;
        }
        int frameWidth;
        int frameHeight;
        if (wholeWindowActive) {
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            if (main == null || framebuffer != main.frameBufferId) {
                return;
            }
            frameWidth = main.width;
            frameHeight = main.height;
        } else {
            if (activeTarget == null || !isWorldFramebuffer(activeTarget, framebuffer)) {
                return;
            }
            frameWidth = activeTarget.viewWidth;
            frameHeight = activeTarget.viewHeight;
        }
        if (realWindowWidth <= 0 || realWindowHeight <= 0) {
            return;
        }

        // Only the "window sized viewport leaked onto the small target" case needs repair, and
        // every viewport request made through GlStateManager is tracked, so no GL round trip is
        // needed here. This runs on every framebuffer bind (Sodium rebinds after each chunk layer
        // and Iris binds its own targets), where glGetIntegerv used to cost real milliseconds.
        if (ScaledRenderTarget.viewportWidth() != realWindowWidth
                || ScaledRenderTarget.viewportHeight() != realWindowHeight) {
            return;
        }
        GlStateManager._viewport(0, 0, frameWidth, frameHeight);
    }

    /**
     * Moves the screenshot's texture read to a scratch unit.
     *
     * <p>Vanilla binds the main colour texture to the currently active unit and never restores
     * it. Sodium and Iris cache which texture they last bound per unit, so writing the screen
     * image to a unit they consider "already bound" desynchronises their cache: the next world
     * frame keeps sampling the screen instead of the block atlas, and the HUD is smeared over
     * every block. Doing the read on unit 11 (the last unit tracked by
     * {@link GlStateManager}) leaves the units world rendering uses untouched. The scratch
     * unit's previous binding is restored afterwards for the same reason.</p>
     */
    public static void saveTextureStateBeforeScreenshot() {
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        // GlStateManager tracks units 0..11; 11 is the least likely one a world shader keeps
        // bound between frames.
        int scratch = GL13C.GL_TEXTURE0 + 11;
        int previousActive = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(scratch);
        screenshotTextureId = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        screenshotTextureUnit = previousActive;
        // Deliberately leave the scratch unit active: takeScreenshot() binds there.
    }

    /** Restores the scratch unit and the active unit saved by the method above. */
    public static void restoreTextureStateAfterScreenshot() {
        if (!RenderSystem.isOnRenderThread() || screenshotTextureUnit < 0) {
            return;
        }
        int previousActive = screenshotTextureUnit;
        int texture = screenshotTextureId;
        screenshotTextureUnit = -1;
        screenshotTextureId = 0;

        int scratch = GL13C.GL_TEXTURE0 + 11;
        GlStateManager._activeTexture(scratch);
        GlStateManager._bindTexture(texture);
        GlStateManager._activeTexture(previousActive);
    }

    /** Drops every GL object; called from the client stopping event so nothing leaks on world exit. */
    public static void shutdown() {
        restoreMainTarget();
        frameWorldTarget = null;
        fastWorldActive = false;
        worldActive = false;
        msrThisFrame = false;
        poseCaptured = false;
        historyValid = false;
        if (fastTarget != null) {
            fastTarget.destroyBuffers();
            fastTarget = null;
        }
        if (scaled != null) {
            scaled.destroyBuffers();
            scaled = null;
        }
        if (fsrTarget != null) {
            fsrTarget.destroyBuffers();
            fsrTarget = null;
        }
        if (fxaaTarget != null) {
            fxaaTarget.destroyBuffers();
            fxaaTarget = null;
        }
        destroyHistoryTargets();
        if (shaders != null) {
            try {
                shaders.close();
            } catch (Throwable ignored) {
            }
            shaders = null;
        }
        shaderFailure = false;
        lastWidth = -1;
        lastHeight = -1;
        lastLevel = null;
        lastUpscaler = null;
        lastUpscalerPercent = -1;
        msrUnsupported = false;
        msrFailureCount = 0;
        msrDepthValidThisFrame = true;
        irisDepthMisses = 0;
        irisThisFrame = false;
        lastIris = false;
        lastUpscalerSsaa = false;
        jitterIndex = 0;

        // MSR2 cleanup
        msr2ThisFrame = false;
        msr2ActiveThisFrame = false;
        msrShaderFailure = false;
        msrHistoryValid = false;
        msrFrameIndex = 0;
        destroyMsrCompute();
        msrComputeFailed = false;
        if (lanczosTableTexture != 0) {
            GL11C.glDeleteTextures(lanczosTableTexture);
            lanczosTableTexture = 0;
        }
        if (msr2History != null) {
            msr2History.destroy();
            msr2History = null;
        }
        if (msr2CurrentColor != null) {
            msr2CurrentColor.destroyBuffers();
            msr2CurrentColor = null;
        }
    }

    // ==================== MSR2 METHODS ====================

    private static void resetMsr2History() {
        msrHistoryValid = false;
        msrFrameIndex = 0;
        if (computeAvailable) {
            // A destroyed/invalidated history texture must not stay attached to image units.
            Msr2TemporalCompute.unbindImage(0, MSR2HistoryTarget.COLOR_FORMAT);
            Msr2TemporalCompute.unbindImage(1, GL30C.GL_R16UI);
        }
        if (msr2History != null) {
            msr2History.destroy();
            msr2History = null;
        }
    }

    private static void destroyMSR2HistoryTargets() {
        if (msr2History != null) {
            msr2History.destroy();
            msr2History = null;
        }
    }

    private static void ensureMSR2HistoryTargets(int width, int height) {
        if (msr2History != null && msr2History.getWidth() == width && msr2History.getHeight() == height) {
            return;
        }
        if (msr2History != null) {
            msr2History.destroy();
        }
        msr2History = MSR2HistoryTarget.create(width, height);
        msrHistoryValid = false;
        msrFrameIndex = 0;
        ModernOPT.LOG.info("[ModernOPT] allocated MSR2 history targets at {}x{}", width, height);
    }

    /** EASU-reconstructed current frame at output resolution, used when MSR2 Lanczos is off. */
    private static void ensureMSR2CurrentColor(int width, int height) {
        if (msr2CurrentColor != null && msr2CurrentColor.width == width
                && msr2CurrentColor.height == height) {
            return;
        }
        if (msr2CurrentColor != null) {
            msr2CurrentColor.destroyBuffers();
        }
        msr2CurrentColor = new HalfFloatTarget(width, height);
    }

    

    private static void reconstructMsr2(RenderTarget main) {
        ShaderLibrary library = shaders;
        if (library == null || !library.hasMsr()) {
            throw new IllegalStateException("MSR shaders not available");
        }
        if (msr2History == null) {
            throw new IllegalStateException("MSR history targets not allocated");
        }
        int outWidth = main.width;
        int outHeight = main.height;
        if (msr2History.getWidth() != outWidth || msr2History.getHeight() != outHeight) {
            // A resize slipped through beginWorldFrame (Alt+Enter, F11, window drag): recreate
            // before anything samples a stale ping-pong pair.
            ensureMSR2HistoryTargets(outWidth, outHeight);
        }

        int sourceWidth = scaled.width;
        int sourceHeight = scaled.height;
        int sourceTexture = scaled.getColorTextureId();
        int depthTexture = scaled.getDepthTextureId();

        // MSR never runs reconstructMsr(), which is where the shared inverse projection is
        // normally built. Compute it here, otherwise reprojection silently uses a stale
        // matrix (identity on the first frame) and the history is sampled at wrong UVs.
        // On the very first world frame the projection may not have been captured yet; that
        // must not kill the whole MSR pass (it used to throw and fall back to FSR 1 forever).
        currentProjection.invert(inverseProjection);
        if (!inverseProjection.isFinite()) {
            inverseProjection.identity();
            msrHistoryValid = false;
        }

        // Iris owns the scene depth buffer. It is at source resolution (the whole Iris pipeline
        // is sized from the redirected main target), so it can be sampled with input UVs.
        if (irisThisFrame) {
            int irisDepth = IrisCompat.depthTextureId();
            if (irisDepth > 0) {
                depthTexture = irisDepth;
            }
        }

        // Below ~1.11x the temporal pass costs more than it saves: a plain sharpened blit is
        // both faster and artefact-free. The first frame still runs temporal to initialise the
        // history because this decision depends only on the current scale, not on history.
        float upscale = (float) outWidth / (float) sourceWidth;
        boolean temporalWorthIt = upscale > 1.11F;
        boolean useCompute = temporalWorthIt && computeAvailable
                && msrComputeProgram != null;
        if (temporalWorthIt && !useCompute) {
            // The output-resolution history path needs compute images and the motion-vector
            // buffer. Keep the old spatial reconstruction as a safe fallback instead of
            // feeding a source-resolution history into the output-resolution spatial pass.
            reconstructSpatial(main, scaled);
            return;
        }

        boolean useLanczos = ModernOptSettings.msr2Lanczos() && library.hasLanczos();
        int currentColorTexture = sourceTexture;
        int currentColorWidth = sourceWidth;
        int currentColorHeight = sourceHeight;
        if (temporalWorthIt && !useLanczos) {
            // EASU base for MSR2: reconstruct the jittered low-resolution frame once at output
            // resolution, then let the temporal pass reuse that image. This is cheaper than the
            // 6x6 Lanczos kernel and much sharper than a plain bilinear fetch.
            ensureMSR2CurrentColor(outWidth, outHeight);
            ShaderLibrary.beginPassState();
            try {
                msr2CurrentColor.bindWrite(true);
                // The temporal history is unjittered, so the prepass must reconstruct the current
                // frame at its unjittered positions: shift the source sampling by the jitter
                // offset, exactly like the Lanczos kernel does inside the compute pass.
                setUniform2f(library.easu, "Jitter", jitterX, jitterY);
                ShaderLibrary.draw(library.easu, sourceTexture, sourceWidth, sourceHeight,
                        outWidth, outHeight, 0.0F, 0.0F);
            } finally {
                ShaderLibrary.endPassState();
            }
            currentColorTexture = msr2CurrentColor.getColorTextureId();
            currentColorWidth = outWidth;
            currentColorHeight = outHeight;
        }

        if (temporalWorthIt) {
            int motionTexture = 0;
            if (ModernOptSettings.msr2MotionVectors() && motionVectors != null) {
                // The entity boxes were collected during the world pass; rasterise them now that
                // the scene depth buffer is complete.
                motionTexture = motionVectors.render(depthTexture, sourceWidth, sourceHeight);
            }
            runComputeTemporal(sourceWidth, sourceHeight, outWidth, outHeight,
                    currentColorTexture, currentColorWidth, currentColorHeight,
                    depthTexture, useLanczos, motionTexture);
            // The just-written ping-pong side becomes the read side for the sharpen pass.
            msr2History.swap();
        }

        int temporalColor = temporalWorthIt ? msr2History.getReadColorTexture() : sourceTexture;

        // ===== SHARPEN / PRESENT =====
        // History is already at output resolution, so this is an RCAS pass (or a plain copy
        // when sharpening is off), not an EASU upsample. Keeping EASU here would blur the
        // detail that the output-resolution history just preserved.
        ShaderLibrary.beginPassState();
        try {
            GlStateManager._colorMask(true, true, true, true);
            main.bindWrite(true);
            if (temporalWorthIt) {
                int outW = outWidth;
                int outH = outHeight;
                if (cachedMsrSharpness > 0) {
                    ShaderLibrary.draw(library.rcas, temporalColor, outW, outH, outW, outH,
                            cachedMsrSharpness / 100.0F, 0.0F);
                } else {
                    ShaderLibrary.draw(library.bilinear, temporalColor, outW, outH, outW, outH,
                            0.0F, 0.0F);
                }
            } else {
                library.msrSpatial.setSampler("TemporalColor", temporalColor);
                library.msrSpatial.setSampler("DepthTexture", depthTexture);
                setUniform2f(library.msrSpatial, "InputSize", (float) sourceWidth, (float) sourceHeight);
                setUniform1f(library.msrSpatial, "Sharpness", cachedMsrSharpness / 100.0F);
                setUniform1f(library.msrSpatial, "EdgeAwareStrength", cachedMsrEdgeAware ? 1.0F : 0.0F);
                setUniform1f(library.msrSpatial, "DepthEdgeThreshold", (float) cachedMsrDepthThreshold);
                library.msrSpatial.apply();
                drawFullscreenQuad();
                library.msrSpatial.clear();
            }
        } finally {
            ShaderLibrary.endPassState();
            main.bindWrite(true);
        }

        if (temporalWorthIt) {
            msrHistoryValid = true;
            msrFrameIndex++;
            // Save the current camera pose for the next frame's reprojection.
            previousProjection.set(currentProjection);
            previousView.set(currentView);
            previousCameraX = currentCameraX;
            previousCameraY = currentCameraY;
            previousCameraZ = currentCameraZ;
        }
    }

    /**
     * GL 4.3 compute temporal pass.
     *
     * <p>Order is mandatory: upload/bind the SSBO, bind every input texture to an explicit
     * unit and pass that unit to the program, bind the write FBO and the two image units, then
     * dispatch an 8x8 workgroup grid and issue a memory barrier before anyone samples the
     * result.</p>
     */
    private static void runComputeTemporal(int sourceWidth, int sourceHeight,
            int outWidth, int outHeight, int currentColorTexture,
            int currentColorWidth, int currentColorHeight, int depthTexture,
            boolean useLanczos, int motionTexture) {
        Msr2TemporalCompute compute = msrComputeProgram;
        if (compute == null) {
            throw new IllegalStateException("MSR compute program is not loaded");
        }

        int historyColorRead = msr2History.getReadColorTexture();
        int historyDepthRead = msr2History.getReadDepthTexture();
        int historyColorWrite = msr2History.getWriteColorTexture();
        int historyDepthWrite = msr2History.getWriteDepthTexture();

        int previousActive = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 8);
        int previousArrayTexture = GL11C.glGetInteger(GL30C.GL_TEXTURE_BINDING_2D_ARRAY);
        GlStateManager._activeTexture(previousActive);
        try {
            // Explicit texture units: the compute program's sampler uniforms are set to these
            // exact indices below, so it can never read a default/unbound unit.
            bindTextureUnit(GL13C.GL_TEXTURE0 + 4, currentColorTexture);
            bindTextureUnit(GL13C.GL_TEXTURE0 + 5, depthTexture);
            bindTextureUnit(GL13C.GL_TEXTURE0 + 6, historyColorRead);
            bindTextureUnit(GL13C.GL_TEXTURE0 + 7, historyDepthRead);
            bindTextureArrayUnit(GL13C.GL_TEXTURE0 + 8, ensureLanczosTable());
            bindTextureUnit(GL13C.GL_TEXTURE0 + 9, motionTexture);

            compute.bind();
            compute.setInt("CurrentColor", 4);
            compute.setInt("CurrentDepth", 5);
            compute.setInt("HistoryColor", 6);
            compute.setInt("HistoryDepth", 7);
            compute.setInt("LanczosTable", 8);
            compute.setInt("MotionVectors", 9);

            compute.setVec2("InputSize", (float) sourceWidth, (float) sourceHeight);
            compute.setVec2("ColorSize", (float) currentColorWidth, (float) currentColorHeight);
            compute.setVec2("OutputSize", (float) outWidth, (float) outHeight);
            compute.setVec2("Jitter", jitterX, jitterY);
            compute.setInt("UseLanczos", useLanczos ? 1 : 0);
            compute.setInt("LanczosRadius", ModernOptSettings.msr2Lanczos3() ? 3 : 2);
            compute.setFloat("VarianceScale", (float) cachedMsrVarianceScale);
            compute.setFloat("DepthThreshold", (float) cachedMsrDepthThreshold);
            compute.setFloat("HistoryWeight", (float) cachedMsrHistoryWeight);

            tempVec3.set((float) (currentCameraX - previousCameraX),
                    (float) (currentCameraY - previousCameraY),
                    (float) (currentCameraZ - previousCameraZ));
            compute.setVec3("CameraDelta", tempVec3.x, tempVec3.y, tempVec3.z);
            compute.setMatrix4("ViewRotation", currentView);
            compute.setMatrix4("PrevViewRotation", previousView);
            compute.setMatrix4("InvProjection", inverseProjection);
            compute.setMatrix4("PrevProjection", previousProjection);
            compute.setFloat("HistoryValid", msrHistoryValid ? 1.0F : 0.0F);
            compute.setFloat("DepthValid", depthTexture != 0 ? 1.0F : 0.0F);
            compute.setFloat("MotionValid", motionTexture != 0 ? 1.0F : 0.0F);

            msr2History.bindWriteForCompute();
            compute.bindImage(0, historyColorWrite, MSR2HistoryTarget.COLOR_FORMAT);
            compute.bindImage(1, historyDepthWrite, GL30C.GL_R16UI);

            // Workgroup size in msr_temporal.comp is 8x8; the history and the dispatch are at the
            // output resolution now.
            int groupsX = (outWidth + 7) / 8;
            int groupsY = (outHeight + 7) / 8;
            GL43.glDispatchCompute(groupsX, groupsY, 1);

            // Without this the spatial pass can sample the history before imageStore lands.
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GL42.GL_TEXTURE_FETCH_BARRIER_BIT
                    | GL43.GL_SHADER_STORAGE_BARRIER_BIT);

            Msr2TemporalCompute.unbindImage(0, MSR2HistoryTarget.COLOR_FORMAT);
            Msr2TemporalCompute.unbindImage(1, GL30C.GL_R16UI);
            compute.unbind();
        } finally {
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + 8);
            GL11C.glBindTexture(GL30C.GL_TEXTURE_2D_ARRAY, previousArrayTexture);
            GlStateManager._activeTexture(previousActive);
        }
    }

    /**
     * Emergency cleanup when the MSR pass throws: detach compute state, unbind the history
     * framebuffer and put the real main target back before the FSR 1 fallback runs.
     */
    private static void releaseMsrState(RenderTarget main) {
        if (msrComputeProgram != null) {
            msrComputeProgram.unbind();
        }
        if (computeAvailable) {
            Msr2TemporalCompute.unbindImage(0, MSR2HistoryTarget.COLOR_FORMAT);
            Msr2TemporalCompute.unbindImage(1, GL30C.GL_R16UI);
        }
        if (msr2History != null) {
            msr2History.unbind();
        }
        // GlStateManager now tracks the default FBO; binding main afterwards is guaranteed to
        // issue a real glBindFramebuffer and restore the native viewport.
        main.bindWrite(true);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
    }

    private static void bindTextureUnit(int glTextureUnit, int texture) {
        GlStateManager._activeTexture(glTextureUnit);
        GlStateManager._bindTexture(texture);
    }

    /**
     * Builds the polyphase Lanczos table: one row per 1/64-pixel phase, six taps at offsets
     * -2..+3. This is the CPU twin of the table sampling in {@code msr_temporal.comp}; both use
     * the same 65 entries, and the shader round-trips the fractional phase to the nearest one.
     */
    private static float[] buildLanczosWeights(int radius) {
        float[] table = new float[LANCZOS_PHASES * LANCZOS_TAPS];
        for (int phase = 0; phase < LANCZOS_PHASES; phase++) {
            double fraction = phase / (double) (LANCZOS_PHASES - 1);
            for (int tap = 0; tap < LANCZOS_TAPS; tap++) {
                double x = (tap - 2) - fraction;
                // Texture layout is width = phase (x, fastest), height = tap (y). Uploading a
                // phase-major array would transpose the table and turn the kernel into a
                // horizontal smear at low render scales.
                table[tap * LANCZOS_PHASES + phase] = (float) lanczosKernel(x, radius);
            }
        }
        return table;
    }

    private static double lanczosKernel(double x, int radius) {
        x = Math.abs(x);
        if (x < 1.0e-5) {
            return 1.0;
        }
        if (x >= radius) {
            return 0.0;
        }
        double px = Math.PI * x;
        return radius * Math.sin(px) * Math.sin(px / radius) / (px * px);
    }

    /**
     * Lazily creates the 65x6x2 R32F polyphase texture. A texture is used rather than a uniform
     * array because dynamically indexed uniform arrays made the NVIDIA compiler lower the whole
     * table onto the local stack and fail with "invalid local parameter number".
     */
    private static int ensureLanczosTable() {
        if (lanczosTableTexture != 0) {
            return lanczosTableTexture;
        }
        int previousActive = GlStateManager._getActiveTexture();
        int previousArrayTexture = GL11C.glGetInteger(GL30C.GL_TEXTURE_BINDING_2D_ARRAY);
        int texture = GL11C.glGenTextures();
        try {
            GL11C.glBindTexture(GL30C.GL_TEXTURE_2D_ARRAY, texture);
            GL11C.glTexParameteri(GL30C.GL_TEXTURE_2D_ARRAY, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL30C.GL_TEXTURE_2D_ARRAY, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL30C.GL_TEXTURE_2D_ARRAY, GL11C.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL30C.GL_TEXTURE_2D_ARRAY, GL11C.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL30C.GL_TEXTURE_2D_ARRAY, org.lwjgl.opengl.GL12C.GL_TEXTURE_WRAP_R, org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE);
            java.nio.FloatBuffer data = org.lwjgl.system.MemoryUtil.memAllocFloat(
                    LANCZOS_PHASES * LANCZOS_TAPS * 2);
            try {
                data.put(LANCZOS_WEIGHTS_2);
                data.put(LANCZOS_WEIGHTS_3);
                data.flip();
                GL30C.glTexImage3D(GL30C.GL_TEXTURE_2D_ARRAY, 0, GL30C.GL_R32F,
                        LANCZOS_PHASES, LANCZOS_TAPS, 2, 0,
                        GL11C.GL_RED, GL11C.GL_FLOAT, data);
            } finally {
                org.lwjgl.system.MemoryUtil.memFree(data);
            }
            lanczosTableTexture = texture;
            return texture;
        } finally {
            GL11C.glBindTexture(GL30C.GL_TEXTURE_2D_ARRAY, previousArrayTexture);
            GlStateManager._activeTexture(previousActive);
        }
    }

    /** Binds a texture to the GL_TEXTURE_2D_ARRAY target on an explicit unit. */
    private static void bindTextureArrayUnit(int glTextureUnit, int texture) {
        GlStateManager._activeTexture(glTextureUnit);
        GL11C.glBindTexture(GL30C.GL_TEXTURE_2D_ARRAY, texture);
    }

    private static void drawFullscreenQuad() {
        com.mojang.blaze3d.vertex.BufferBuilder buffer = RenderSystem.renderThreadTesselator()
                .begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.BLIT_SCREEN);
        buffer.addVertex(0.0F, 0.0F, 0.0F);
        buffer.addVertex(1.0F, 0.0F, 0.0F);
        buffer.addVertex(1.0F, 1.0F, 0.0F);
        buffer.addVertex(0.0F, 1.0F, 0.0F);
        com.mojang.blaze3d.vertex.BufferUploader.draw(buffer.buildOrThrow());
    }

    /**
     * Fast blit using glBlitFramebuffer for TBDR-friendly final output.
     * Avoids fragment shader overhead for simple copies.
     */
    private static void blitFramebuffer(int sourceTexture, int targetFramebuffer,
                                        int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        RenderSystem.assertOnRenderThread();
        org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER, 0);
        org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, targetFramebuffer);
        org.lwjgl.opengl.GL11C.glReadBuffer(org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0);
        org.lwjgl.opengl.GL30C.glBlitFramebuffer(
                0, 0, sourceWidth, sourceHeight,
                0, 0, targetWidth, targetHeight,
                org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT,
                org.lwjgl.opengl.GL11C.GL_LINEAR
        );
        org.lwjgl.opengl.GL30C.glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER, 0);
    }

    // ===== Safe Uniform Setters (null-checked, like ShaderLibrary.draw) =====

    private static void setUniform2f(ShaderInstance shader, String name, float x, float y) {
        Uniform u = shader.getUniform(name);
        if (u != null) u.set(x, y);
    }

    private static void setUniform1f(ShaderInstance shader, String name, float x) {
        Uniform u = shader.getUniform(name);
        if (u != null) u.set(x);
    }

    private static void setUniform3f(ShaderInstance shader, String name, float x, float y, float z) {
        Uniform u = shader.getUniform(name);
        if (u != null) u.set(x, y, z);
    }

    private static void setUniformMatrix4f(ShaderInstance shader, String name, Matrix4f mat) {
        Uniform u = shader.getUniform(name);
        if (u != null) u.set(mat);
    }
}







