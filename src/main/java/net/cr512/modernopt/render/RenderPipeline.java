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
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.world.level.Level;
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
 *   <li>Right before {@code LevelRenderer#renderLevel}: for FSR 2, offset the projection matrix
 *       by a sub-pixel Halton jitter and remember the camera pose.</li>
 *   <li>Right before the hand pass: finish the FSR 2 chain into the real main target, so the
 *       depth buffer is still available for reprojection and the hand is rendered natively.</li>
 *   <li>{@code GameRenderer#render} right before {@code Gui#render}: the remaining filters
 *       resolve MSAA if needed, run the reconstruction filter into the real main target and
 *       bind it again, so the HUD and screens stay at native resolution.</li>
 * </ol>
 */
public final class RenderPipeline {

    private static ScaledRenderTarget scaled;
    private static TextureTarget fsrTarget;
    private static TextureTarget fxaaTarget;
    private static HalfFloatTarget historyA;
    private static HalfFloatTarget historyB;
    private static HalfFloatTarget historyFront;
    private static HalfFloatTarget historyBack;
    private static ShaderLibrary shaders;
    private static boolean shaderFailure;
    private static boolean worldActive;
    private static boolean loggedIrisSkip;
    private static boolean irisThisFrame;
    private static boolean loggedIrisDepth;
    private static boolean lastIris;
    private static boolean loggedFabulousSkip;
    private static int lastWidth = -1;
    private static int lastHeight = -1;

    /** Temporal upscaling state. */
    private static boolean fsr2ThisFrame;
    private static boolean fsr2ActiveThisFrame;
    private static boolean fsr2Unsupported;
    private static boolean poseCaptured;
    private static boolean historyValid;
    private static boolean loggedFsr2Active;
    private static boolean loggedFsr2Iris;
    private static boolean loggedFsr2Msaa;
    private static boolean loggedFsr2Fallback;
    private static boolean loggedProjectionGuard;
    private static int fsr2FailureCount;
    private static boolean fsr2DepthValidThisFrame = true;
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
    private static float previousJitterX;
    private static float previousJitterY;
    private static int jitterIndex;

    // R2 (Sobol) jitter sequence - better distribution than Halton, less clustering
    private static final float[][] R2_JITTER = new float[64][2];

    // MSR 2.0 state
    private static boolean msrThisFrame;
    private static boolean msrActiveThisFrame;
    private static MSRHistoryTarget msrHistory;
    private static boolean msrShaderFailure;
    private static boolean msrHistoryValid;
    private static int msrFrameIndex;
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

    // MSR 2.0 cached settings
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
    private static MotionVectorCollector motionVectorCollector;
    private static MsrTemporalCompute msrComputeProgram;
    private static boolean msrComputeFailed;

    private RenderPipeline() {
    }

    /** Internal world resolution, for the overlay and for logging. */
    public static int internalWidth() {
        return scaled != null ? scaled.width : -1;
    }

    public static int internalHeight() {
        return scaled != null ? scaled.height : -1;
    }

    public static int activeSamples() {
        return scaled != null ? scaled.activeSamples() : 1;
    }

    /**
     * Label for the overlay: the upscaler that actually produced the last world frame.
     * Keeps the UI honest when FSR 2 fell back to FSR 1 (SSAA or a pass failure).
     */
    public static String activeUpscalerLabel() {
        UpscalerType requested = ModernOptSettings.upscaler();
        if (requested == UpscalerType.FSR2 && !fsr2ActiveThisFrame) {
            return ModernOptSettings.ssaa() ? "FSR 2 off (SSAA)" : "FSR 2 off (FSR 1)";
        }
        if (requested == UpscalerType.FSR2 && !fsr2DepthValidThisFrame) {
            return "FSR 2 (no depth)";
        }
        return requested.label();
    }

    /** The target the world should render into, or {@code null} when vanilla rendering is fine. */
    public static RenderTarget currentWorldTarget() {
        return worldActive ? scaled : null;
    }

    /** Get the motion vector collector for entity motion recording. */
    public static MotionVectorCollector getMotionVectorCollector() {
        return motionVectorCollector;
    }

    /** Get the current projection matrix (captured during jitterProjection). */
    public static Matrix4f getCurrentProjection() {
        return currentProjection;
    }

    /** Get the current view matrix (captured during jitterProjection). */
    public static Matrix4f getCurrentView() {
        return currentView;
    }

    /** Called at the head of {@code GameRenderer#renderLevel}. */
    public static void beginWorldFrame() {
        worldActive = false;
        fsr2ThisFrame = false;
        msrThisFrame = false;
        msrActiveThisFrame = false;
        poseCaptured = false;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.noRender) {
            resetHistory();
            return;
        }

        // Cache settings once per frame
        ModernOptSettings.refresh();
        if (!ModernOptSettings.enabled()) {
            resetHistory();
            return;
        }
        
        // Fast settings cache - only refresh when actually changed
        cacheSettings();
        
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
            resetMsrHistory();
            lastUpscaler = null;
        }

        UpscalerType requested = cachedUpscaler;
        boolean iris = IrisCompat.isShaderPackInUse();
        irisThisFrame = iris;
        if (iris != lastIris) {
            lastIris = iris;
            resetHistory();
            resetMsrHistory();
        }
        boolean fsr2Wanted = requested == UpscalerType.FSR2 && !cachedSsaa;
        if (requested == UpscalerType.FSR2 && iris && !loggedFsr2Iris) {
            loggedFsr2Iris = true;
            ModernOPT.LOG.info("[ModernOPT] FSR 2 is active with the Iris shader pack: using Iris depth for "
                    + "reprojection and letting the pack's own temporal filter handle sub-pixel jitter");
        }
        if (fsr2Wanted && cachedMsaaSamples > 1 && !loggedFsr2Msaa) {
            loggedFsr2Msaa = true;
            ModernOPT.LOG.info("[ModernOPT] MSAA is off in FSR 2 mode (the temporal pass needs a single sampled depth buffer)");
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
        if (fsr2Wanted) {
            samples = 1;
        }

        if (iris) {
            logIrisOnce("shader pack detected, scaling the Iris pipeline as well");
        }

        if (fsr2Wanted && (!ensureShaders(minecraft) || shaders == null || !shaders.hasTemporal())) {
            if (shaders == null || !shaders.isValid()) {
                return;
            }
            fsr2Unsupported = true;
            fsr2Wanted = false;
            if (!loggedFsr2Fallback) {
                loggedFsr2Fallback = true;
                ModernOPT.LOG.warn("[ModernOPT] FSR 2 temporal pass unavailable, falling back to FSR 1");
            }
        }

        int[] size = RenderScaleMath.targetSize(windowWidth, windowHeight, cachedRenderScale,
                cachedSsaa, RenderSystem.maxSupportedTextureSize());

        // MSR 2.0 takes priority over other upscalers when enabled
        if (cachedMsrEnabled) {
            // MSR uses the same scaled target but different reconstruction
            // Force single sample for MSR temporal pass
            samples = 1;
            fsr2Wanted = false;
            // At 100% render scale there is nothing to reconstruct: MSR must be a pure
            // passthrough here so it can never be slower than native rendering.
            if (size[0] >= windowWidth && size[1] >= windowHeight) {
                resetHistory();
                resetMsrHistory();
                rememberUpscalerSettings(requested);
                return;
            }
        }

        boolean useful = size[0] != windowWidth || size[1] != windowHeight || samples > 1
                || cachedFxaa || fsr2Wanted || cachedMsrEnabled;
        if (!useful) {
            resetHistory();
            resetMsrHistory();
            rememberUpscalerSettings(requested);
            return;
        }

        ensureScaledTarget(size[0], size[1], samples);
        if (scaled == null) {
            resetHistory();
            resetMsrHistory();
            return;
        }

        // Reset temporal history before allocating/reallocating the MSR targets. Resetting after
        // ensureMsrHistoryTargets() would destroy the freshly created ping-pong pair.
        boolean settingsChanged = requested != lastUpscaler
                || cachedRenderScale != lastUpscalerPercent
                || cachedSsaa != lastUpscalerSsaa;
        rememberUpscalerSettings(requested);
        if (!fsr2Wanted) {
            resetHistory();
        } else if (settingsChanged) {
            resetHistory();
        }
        if (settingsChanged) {
            // MSR history must not survive a scale or upscaler change either.
            resetMsrHistory();
        }

        // Initialize MSR history targets if needed
        if (cachedMsrEnabled) {
            ensureMsrHistoryTargets(size[0], size[1]);
            msrThisFrame = true;
            fsr2Wanted = false;
        }

        scaled.setLinearFilter(requested != UpscalerType.NEAREST);
        fsr2ThisFrame = fsr2Wanted;
        int outputWidth = minecraft.getMainRenderTarget().width;
        int outputHeight = minecraft.getMainRenderTarget().height;
        worldActive = true;
        scaled.bindWrite(true);

        if (fsr2ThisFrame && !loggedFsr2Active) {
            loggedFsr2Active = true;
            ModernOPT.LOG.info("[ModernOPT] FSR 2 temporal upscaling active ({}x{} -> {}x{}, camera-reconstructed motion)",
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

        // MSR 2.0
        cachedMsrEnabled = ModernOptSettings.msrEnabled() || (cachedUpscaler == UpscalerType.MSR);
        cachedMsrVarianceScale = ModernOptSettings.msrVarianceScale();
        cachedMsrEdgeAware = ModernOptSettings.msrEdgeAware();
        cachedMsrSharpness = ModernOptSettings.msrSharpness();
        cachedMsrDepthThreshold = ModernOptSettings.msrDepthThreshold();
        cachedMsrHistoryWeight = ModernOptSettings.msrHistoryWeight();

        // DRS
        cachedMsrDynamicRes = ModernOptSettings.msrDynamicRes();
        cachedMsrTemporalMinFps = ModernOptSettings.msrTemporalMinFps();
        cachedMsrScaleStep = ModernOptSettings.msrScaleStep();
        cachedMsrMinScale = ModernOptSettings.msrMinScale();
// Compute shader detection (GL 4.3+)
        if (!computeAvailable) {
            try {
                computeAvailable = org.lwjgl.opengl.GL.getCapabilities().OpenGL43;
                if (computeAvailable) {
                    motionVectorCollector = new MotionVectorCollector(true);
                    ModernOPT.LOG.info("[ModernOPT] MSR: GL 4.3+ compute shaders available");
                } else {
                    ModernOPT.LOG.info("[ModernOPT] MSR: GL 4.3+ not available, using fragment shader fallback");
                }
            } catch (Throwable t) {
                computeAvailable = false;
                ModernOPT.LOG.warn("[ModernOPT] MSR: compute detection failed", t);
            }
        }
        if (motionVectorCollector != null) {
            motionVectorCollector.beginFrame();
        }
    }

    /**
     * Applies the FSR 2 sub-pixel jitter to the projection matrix that is about to be used by the
     * world pass and records the pose needed for reprojection.
     *
     * <p>Called from the {@code LevelRenderer#renderLevel} call site, i.e. after vanilla applied
     * view bobbing and nausea to the projection. The returned copy is only used by the world
     * pass; the hand pass gets the untouched matrix.</p>
     */
    public static Matrix4f jitterProjection(Matrix4f projection, Camera camera) {
        if (!fsr2ThisFrame && !msrThisFrame || scaled == null) {
            return projection;
        }

        // Capture camera pose for both FSR 2 (reprojection + jitter) and MSR 2.0 (reprojection only).
        // MSR does not apply sub-pixel jitter, but it still needs the view/projection/camera state
        // so its temporal pass can reproject history pixels to the previous frame.
        tempQuat.set(camera.rotation()).conjugate();
        currentView.set(tempQuat);
        currentProjection.set(projection);
        currentCameraX = camera.getPosition().x;
        currentCameraY = camera.getPosition().y;
        currentCameraZ = camera.getPosition().z;

        if (!fsr2ThisFrame) {
            // MSR path: pose captured, no jitter applied
            poseCaptured = true;
            return projection;
        }

        if (!isUsableProjection(projection)) {
            resetHistory();
            if (!loggedProjectionGuard) {
                loggedProjectionGuard = true;
                ModernOPT.LOG.debug("[ModernOPT] skipping FSR 2 on a degenerate projection matrix (first frame)");
            }
            return projection;
        }

        if (irisThisFrame) {
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
     * itself should stay at native resolution. Previously only FSR 2 stopped here; MSR ran
     * after the hand and therefore read an already-cleared (all-sky) depth buffer.
     */
    public static void finishBeforeHand() {
        if (!worldActive || scaled == null) {
            return;
        }
        if (!fsr2ThisFrame && !msrThisFrame) {
            return;
        }
        finishWorldFrame();
    }

    /** Called right before {@code Gui#render}, i.e. after the world and before the HUD. */
    public static void endWorldFrame() {
        finishWorldFrame();
    }

    private static void finishWorldFrame() {
        if (!worldActive || scaled == null) {
            return;
        }

        boolean temporal = fsr2ThisFrame && poseCaptured && !fsr2Unsupported;
        boolean jittered = poseCaptured;
        fsr2ActiveThisFrame = temporal;
        worldActive = false;
        fsr2ThisFrame = false;
        poseCaptured = false;

        // Handle MSR 2.0
        if (msrThisFrame) {
            msrActiveThisFrame = true;
            msrThisFrame = false;
            // MSR doesn't use jitter, but its temporal pass still needs the camera pose
            // captured above (currentView / currentProjection / currentCamera*).
            jittered = false; // MSR does not modify the projection matrix
        }

        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.getMainRenderTarget();
        if (!ensureShaders(minecraft)) {
            restoreProjection(jittered);
            main.bindWrite(true);
            return;
        }

        try {
            scaled.resolveMsaa();
            if (temporal) {
                try {
                    reconstructFsr2(main);
                    fsr2FailureCount = 0;
                } catch (Throwable throwable) {
                    resetHistory();
                    fsr2ActiveThisFrame = false;
                    fsr2FailureCount++;
                    if (fsr2FailureCount >= 3) {
                        fsr2Unsupported = true;
                    }
                    if (!loggedFsr2Fallback) {
                        loggedFsr2Fallback = true;
                        ModernOPT.LOG.error("[ModernOPT] FSR 2 pass failed, falling back to FSR 1"
                                + (fsr2FailureCount >= 3 ? " for this session" : " for this frame"), throwable);
                    } else {
                        ModernOPT.LOG.error("[ModernOPT] FSR 2 pass failed again (loggedFsr2Fallback already true): " + throwable.getMessage());
                    }
                    reconstructSpatial(main);
                }
            } else if (msrActiveThisFrame) {
                try {
                    reconstructMsr(main);
                    fsr2FailureCount = 0; // reuse: reset failure counter on success
                } catch (Throwable throwable) {
                    msrShaderFailure = true;
                    // Never leave the MSR history FBO / compute program / image units bound:
                    // the FSR 1 fallback below must draw into the real main target.
                    releaseMsrState(main);
                    resetMsrHistory();
                    ModernOPT.LOG.error("[ModernOPT] MSR 2.0 pass failed, falling back to FSR 1", throwable);
                    try {
                        reconstructSpatial(main);
                    } catch (Throwable fallbackFailure) {
                        ModernOPT.LOG.error("[ModernOPT] FSR 1 fallback after MSR failure also failed", fallbackFailure);
                    }
                }
                msrActiveThisFrame = false;
            } else {
                reconstructSpatial(main);
            }
        } catch (Throwable throwable) {
            ModernOPT.LOG.error("[ModernOPT] reconstruction failed, falling back to a plain blit", throwable);
            shaderFailure = true;
        } finally {
            restoreProjection(jittered);
            main.bindWrite(true);
        }
    }

    private static void restoreProjection(boolean jittered) {
        if (jittered) {
            RenderSystem.setProjectionMatrix(currentProjection, VertexSorting.DISTANCE_TO_ORIGIN);
        }
    }

    /**
     * FSR 2 chain: EASU to output resolution, temporal accumulation against the reprojected
     * history, then RCAS (and optionally FXAA). Runs in the output resolution for the whole
     * temporal stage, so the history is not limited by the internal resolution.
     */
    private static void reconstructFsr2(RenderTarget main) {
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
                            + "FSR 2 reprojection is rotation-only (disocclusion test disabled)");
                }
            }
        } else {
            irisDepthMisses = 0;
        }
        fsr2DepthValidThisFrame = depthValid;

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

        ShaderLibrary.beginPassState();
        try {
            easu.bindWrite(true);
            ShaderLibrary.draw(library.easu, sourceTexture, sourceWidth, sourceHeight,
                    outWidth, outHeight, 0.0F, 0.0F);

            write.bindWrite(true);
            ShaderLibrary.drawTemporal(library.temporal,
                    easu.getColorTextureId(), read.getColorTextureId(), depthTexture,
                    sourceWidth, sourceHeight, outWidth, outHeight,
                    inverseProjection, previousProjection, currentView, previousView,
                    tempVec3.x, tempVec3.y, tempVec3.z,
                    jitterX, jitterY, previousJitterX, previousJitterY,
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
        previousJitterX = jitterX;
        previousJitterY = jitterY;

        if (outWidth != lastWidth || outHeight != lastHeight) {
            lastWidth = outWidth;
            lastHeight = outHeight;
            ModernOPT.LOG.info("[ModernOPT] presenting {}x{} world image at {}x{} (FSR 2)",
                    sourceWidth, sourceHeight, outWidth, outHeight);
        }
    }

    /** Spatial reconstruction used by every non-temporal mode, and by the FSR 2 fallback. */
    private static void reconstructSpatial(RenderTarget main) {
        int outWidth = main.width;
        int outHeight = main.height;
        int sourceTexture = scaled.getColorTextureId();
        int sourceWidth = scaled.width;
        int sourceHeight = scaled.height;

        float softness = cachedSoftness / 100.0F;
        float rcasSharpness = cachedFsrRcasSharpness / 100.0F;
        boolean fxaa = cachedFxaa;
        ShaderLibrary library = shaders;
        UpscalerType upscaler = cachedUpscaler;
        boolean fsr1Path = !cachedSsaa
                && (upscaler == UpscalerType.FSR1 || upscaler == UpscalerType.FSR2);

        // At small upscale factors (~1.25x), EASU is slower than bilinear - use simpler filter
        float upscale = (float) outWidth / (float) sourceWidth;
        boolean simpleUpscale = upscale < 1.11F && fsr1Path;

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
                } else {
                    // Full FSR 1 EASU + RCAS path
                    TextureTarget easu = ensureIntermediates(outWidth, outHeight, true);
                    easu.bindWrite(true);
                    ShaderLibrary.draw(library.easu, sourceTexture, sourceWidth, sourceHeight, outWidth, outHeight, 0.0F, 0.0F);

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
            }
        } finally {
            ShaderLibrary.endPassState();
            main.bindWrite(true);
        }

        if (outWidth != lastWidth || outHeight != lastHeight) {
            lastWidth = outWidth;
            lastHeight = outHeight;
            ModernOPT.LOG.info("[ModernOPT] presenting {}x{} world image at {}x{} (samples={})",
                    sourceWidth, sourceHeight, outWidth, outHeight, scaled.activeSamples());
        }
    }

    private static ShaderInstance filterFor(ShaderLibrary library, UpscalerType type) {
        return switch (type) {
            case NEAREST -> library.nearest;
            case SOFT -> library.soft;
            case BILINEAR, FSR1, FSR2, MSR -> library.bilinear;
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
        ModernOPT.LOG.info("[ModernOPT] allocated FSR 2 history targets at {}x{}", width, height);
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

    private static void ensureScaledTarget(int width, int height, int samples) {
        if (scaled != null && scaled.width == width && scaled.height == height
                && scaled.requestedSamples() == Math.max(1, samples) && scaled.isMultisampled() == (samples > 1)) {
            return;
        }
        if (scaled != null) {
            scaled.destroyBuffers();
            scaled = null;
        }
        resetHistory();
        try {
            scaled = new ScaledRenderTarget(width, height, samples);
        } catch (Throwable throwable) {
            ModernOPT.LOG.error("[ModernOPT] cannot allocate a {}x{} render target, render scaling disabled until restart",
                    width, height, throwable);
            scaled = null;
        }
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
        if (!computeAvailable) {
            msrComputeProgram = null;
            msrComputeFailed = false;
            return;
        }
        msrComputeFailed = false;
        try {
            msrComputeProgram = MsrTemporalCompute.load(resources);
            ModernOPT.LOG.info("[ModernOPT] MSR: GL 4.3 compute temporal shader compiled");
        } catch (Throwable throwable) {
            msrComputeProgram = null;
            msrComputeFailed = true;
            ModernOPT.LOG.warn("[ModernOPT] MSR: compute temporal unavailable, using the fragment fallback",
                    throwable);
        }
    }

    private static void destroyMsrCompute() {
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
     * Keeps the GL viewport matched to the scaled target.
     *
     * <p>Vanilla passes occasionally bind a window sized render target in the middle of the world
     * pass and then return with {@code bindWrite(false)}, which deliberately leaves the viewport
     * untouched. The terrain still looks right because it was already drawn, but every later pass
     * (entities, hand, particles) is then rasterised for a target twice as large and is clipped
     * away outside a corner. Forcing the size back whenever the scaled target is bound fixes that
     * without touching any other framebuffer (Iris gbuffers, shadow maps, post chains).</p>
     *
     * @return {@code true} when this call already applied the corrected viewport
     */
    public static boolean clampViewport(int x, int y, int width, int height) {
        ScaledRenderTarget target = scaled;
        if (!worldActive || target == null) {
            return false;
        }
        if (width == target.viewWidth && height == target.viewHeight) {
            return false;
        }
        if (!target.isCurrentDrawTarget()) {
            return false;
        }

        GlStateManager._viewport(0, 0, target.viewWidth, target.viewHeight);
        return true;
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
        worldActive = false;
        fsr2ThisFrame = false;
        poseCaptured = false;
        historyValid = false;
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
        fsr2Unsupported = false;
        fsr2FailureCount = 0;
        fsr2DepthValidThisFrame = true;
        irisDepthMisses = 0;
        irisThisFrame = false;
        lastIris = false;
        lastUpscalerSsaa = false;
        jitterIndex = 0;

        // MSR 2.0 cleanup
        msrThisFrame = false;
        msrActiveThisFrame = false;
        msrShaderFailure = false;
        msrHistoryValid = false;
        msrFrameIndex = 0;
        destroyMsrCompute();
        msrComputeFailed = false;
        if (msrHistory != null) {
            msrHistory.destroy();
            msrHistory = null;
        }
    }

    // ==================== MSR 2.0 METHODS ====================

    private static void resetMsrHistory() {
        msrHistoryValid = false;
        msrFrameIndex = 0;
        if (computeAvailable) {
            // A destroyed/invalidated history texture must not stay attached to image units.
            MsrTemporalCompute.unbindImage(0, GL30C.GL_R11F_G11F_B10F);
            MsrTemporalCompute.unbindImage(1, GL30C.GL_R16UI);
        }
        if (msrHistory != null) {
            msrHistory.destroy();
            msrHistory = null;
        }
    }

    private static void destroyMsrHistoryTargets() {
        if (msrHistory != null) {
            msrHistory.destroy();
            msrHistory = null;
        }
    }

    private static void ensureMsrHistoryTargets(int width, int height) {
        if (msrHistory != null && msrHistory.getWidth() == width && msrHistory.getHeight() == height) {
            return;
        }
        if (msrHistory != null) {
            msrHistory.destroy();
        }
        msrHistory = MSRHistoryTarget.create(width, height);
        msrHistoryValid = false;
        msrFrameIndex = 0;
        ModernOPT.LOG.info("[ModernOPT] allocated MSR 2.0 history targets at {}x{}", width, height);
    }

    

    private static void reconstructMsr(RenderTarget main) {
        ShaderLibrary library = shaders;
        if (library == null || !library.hasMsr()) {
            throw new IllegalStateException("MSR shaders not available");
        }
        if (msrHistory == null) {
            throw new IllegalStateException("MSR history targets not allocated");
        }
        if (msrHistory.getWidth() != scaled.width || msrHistory.getHeight() != scaled.height) {
            // A resize slipped through beginWorldFrame (Alt+Enter, F11, window drag): recreate
            // before anything samples a stale ping-pong pair.
            ensureMsrHistoryTargets(scaled.width, scaled.height);
        }

        int outWidth = main.width;
        int outHeight = main.height;
        int sourceWidth = scaled.width;
        int sourceHeight = scaled.height;
        int sourceTexture = scaled.getColorTextureId();
        int depthTexture = scaled.getDepthTextureId();

        // MSR never runs reconstructFsr2(), which is where the shared inverse projection is
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
                && msrComputeProgram != null && motionVectorCollector != null;

        if (temporalWorthIt) {
            if (useCompute) {
                runComputeTemporal(sourceWidth, sourceHeight, outWidth, outHeight,
                        sourceTexture, depthTexture);
            } else {
                runFragmentTemporal(library, sourceWidth, sourceHeight, outWidth, outHeight,
                        sourceTexture, depthTexture);
            }
            // The just-written ping-pong side becomes the read side for the spatial pass.
            msrHistory.swap();
        }

        int temporalColor = temporalWorthIt ? msrHistory.getReadColorTexture() : sourceTexture;

        // ===== SPATIAL PASS (EASU + CAS) - always a fragment shader =====
        ShaderLibrary.beginPassState();
        try {
            // The final pass writes the real main target, so alpha must be written as well;
            // beginPassState() disables alpha writes for the history passes.
            GlStateManager._colorMask(true, true, true, true);

            // Native resolution viewport and the real game framebuffer. main.bindWrite() goes
            // through GlStateManager, which now agrees with the FBO actually bound because
            // MSRHistoryTarget also uses GlStateManager.
            main.bindWrite(true);

            library.msrSpatial.setSampler("TemporalColor", temporalColor);
            library.msrSpatial.setSampler("DepthTexture", depthTexture);

            setUniform2f(library.msrSpatial, "InputSize", (float) sourceWidth, (float) sourceHeight);
            setUniform1f(library.msrSpatial, "Sharpness", cachedMsrSharpness / 100.0F);
            setUniform1f(library.msrSpatial, "EdgeAwareStrength", cachedMsrEdgeAware ? 1.0F : 0.0F);
            setUniform1f(library.msrSpatial, "DepthEdgeThreshold", (float) cachedMsrDepthThreshold);

            library.msrSpatial.apply();
            drawFullscreenQuad();
            library.msrSpatial.clear();
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
            int outWidth, int outHeight, int sourceTexture, int depthTexture) {
        MsrTemporalCompute compute = msrComputeProgram;
        if (compute == null) {
            throw new IllegalStateException("MSR compute program is not loaded");
        }

        int historyColorRead = msrHistory.getReadColorTexture();
        int historyDepthRead = msrHistory.getReadDepthTexture();
        int historyColorWrite = msrHistory.getWriteColorTexture();
        int historyDepthWrite = msrHistory.getWriteDepthTexture();

        int previousActive = GlStateManager._getActiveTexture();
        try {
            // Explicit texture units: the compute program's sampler uniforms are set to these
            // exact indices below, so it can never read a default/unbound unit.
            bindTextureUnit(GL13C.GL_TEXTURE0 + 4, sourceTexture);
            bindTextureUnit(GL13C.GL_TEXTURE0 + 5, depthTexture);
            bindTextureUnit(GL13C.GL_TEXTURE0 + 6, historyColorRead);
            bindTextureUnit(GL13C.GL_TEXTURE0 + 7, historyDepthRead);

            if (motionVectorCollector != null) {
                motionVectorCollector.uploadToSsbo();
                motionVectorCollector.bindSsbo(0);
            }

            compute.bind();
            compute.setInt("CurrentColor", 4);
            compute.setInt("CurrentDepth", 5);
            compute.setInt("HistoryColor", 6);
            compute.setInt("HistoryDepth", 7);
            // The shader loops over this instead of over the fixed 1024-entry pool.
            compute.setInt("EntityCount", motionVectorCollector != null
                    ? motionVectorCollector.getEntityCount() : 0);

            compute.setVec2("InputSize", (float) sourceWidth, (float) sourceHeight);
            compute.setVec2("Jitter", 0.0F, 0.0F);
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

            msrHistory.bindWriteForCompute();
            compute.bindImage(0, historyColorWrite, GL30C.GL_R11F_G11F_B10F);
            compute.bindImage(1, historyDepthWrite, GL30C.GL_R16UI);

            // Workgroup size in msr_temporal.comp is 8x8.
            int groupsX = (sourceWidth + 7) / 8;
            int groupsY = (sourceHeight + 7) / 8;
            GL43.glDispatchCompute(groupsX, groupsY, 1);

            // Without this the spatial pass can sample the history before imageStore lands.
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GL42.GL_TEXTURE_FETCH_BARRIER_BIT
                    | GL43.GL_SHADER_STORAGE_BARRIER_BIT);

            MsrTemporalCompute.unbindImage(0, GL30C.GL_R11F_G11F_B10F);
            MsrTemporalCompute.unbindImage(1, GL30C.GL_R16UI);
            compute.unbind();
        } finally {
            GlStateManager._activeTexture(previousActive);
        }
    }

    /** Fragment-shader fallback for GPUs without a usable GL 4.3 compute program. */
    private static void runFragmentTemporal(ShaderLibrary library, int sourceWidth, int sourceHeight,
            int outWidth, int outHeight, int sourceTexture, int depthTexture) {
        int historyColorRead = msrHistory.getReadColorTexture();
        int historyDepthRead = msrHistory.getReadDepthTexture();

        ShaderLibrary.beginPassState();
        try {
            // Both MRT attachments (colour + R16UI depth) are written by this pass.
            GlStateManager._colorMask(true, true, true, true);
            msrHistory.bindWrite();

            library.msrTemporal.setSampler("CurrentColor", sourceTexture);
            library.msrTemporal.setSampler("CurrentDepth", depthTexture);
            library.msrTemporal.setSampler("HistoryColor", historyColorRead);
            library.msrTemporal.setSampler("HistoryDepth", historyDepthRead);

            setUniform2f(library.msrTemporal, "InputSize", (float) sourceWidth, (float) sourceHeight);
            setUniform2f(library.msrTemporal, "Jitter", 0.0F, 0.0F);
            setUniform1f(library.msrTemporal, "VarianceScale", (float) cachedMsrVarianceScale);
            setUniform1f(library.msrTemporal, "DepthThreshold", (float) cachedMsrDepthThreshold);
            setUniform1f(library.msrTemporal, "HistoryWeight", (float) cachedMsrHistoryWeight);

            tempVec3.set((float) (currentCameraX - previousCameraX),
                    (float) (currentCameraY - previousCameraY),
                    (float) (currentCameraZ - previousCameraZ));
            setUniform3f(library.msrTemporal, "CameraDelta", tempVec3.x, tempVec3.y, tempVec3.z);
            setUniformMatrix4f(library.msrTemporal, "ViewRotation", currentView);
            setUniformMatrix4f(library.msrTemporal, "PrevViewRotation", previousView);
            setUniformMatrix4f(library.msrTemporal, "InvProjection", inverseProjection);
            setUniformMatrix4f(library.msrTemporal, "PrevProjection", previousProjection);
            setUniform1f(library.msrTemporal, "HistoryValid", msrHistoryValid ? 1.0F : 0.0F);
            setUniform1f(library.msrTemporal, "DepthValid", depthTexture != 0 ? 1.0F : 0.0F);

            library.msrTemporal.apply();
            drawFullscreenQuad();
            library.msrTemporal.clear();
        } finally {
            ShaderLibrary.endPassState();
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
            MsrTemporalCompute.unbindImage(0, GL30C.GL_R11F_G11F_B10F);
            MsrTemporalCompute.unbindImage(1, GL30C.GL_R16UI);
        }
        if (msrHistory != null) {
            msrHistory.unbind();
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







