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
 *       by a sub-pixel Halton jitter and remember the camera pose.</li>
 *   <li>Right before the hand pass: finish the MSR chain into the real main target, so the
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
    private static float previousJitterX;
    private static float previousJitterY;
    private static int jitterIndex;

    // R2 (Sobol) jitter sequence - better distribution than Halton, less clustering
    private static final float[][] R2_JITTER = new float[64][2];

    // MSR2 state
    private static boolean msr2ThisFrame;
    private static boolean msr2ActiveThisFrame;
    private static MSR2HistoryTarget msr2History;
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

    /** TEMPORARY DIAGNOSTIC counter for per-frame GL logging. */
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
        return windowScaled && scaled != null ? scaled.viewWidth : -1;
    }
    /** Returns the height {@code Window#getHeight} should report, or {@code -1} for the real one. */
    public static int windowHeightOverride() {
        if (wholeWindowActive) {
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            return main != null ? main.height : -1;
        }
        return windowScaled && scaled != null ? scaled.viewHeight : -1;
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
    private static MotionVectorCollector motionVectorCollector;
    private static Msr2TemporalCompute msrComputeProgram;
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
     * Keeps the UI honest when MSR fell back to FSR 1 (SSAA or a pass failure).
     */
    public static String activeUpscalerLabel() {
        UpscalerType requested = ModernOptSettings.upscaler();
        if (requested == UpscalerType.MSR && !msrActiveThisFrame) {
            return ModernOptSettings.ssaa() ? "MSR off (SSAA)" : "MSR off (FSR 1)";
        }
        if (requested == UpscalerType.MSR && !msrDepthValidThisFrame) {
            return "MSR (no depth)";
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
        msrThisFrame = false;
        msr2ThisFrame = false;
        msr2ActiveThisFrame = false;
        poseCaptured = false;
        // Never let the window-size override leak from a frame that was skipped or aborted:
        // otherwise the next frame reads the *scaled* width back and halves the target again.
        windowScaled = false;

        GpuProfiler.maybeLog();
        RenderStageProfiler.maybeLog();

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.noRender) {
            resetHistory();
            return;
        }
        if (minecraft.gameRenderer != null && minecraft.gameRenderer.getMainCamera() != null) {
            net.minecraft.world.phys.Vec3 shadowCamera = minecraft.gameRenderer.getMainCamera().getPosition();
            shadowHoldCheckCamera(shadowCamera.x, shadowCamera.y, shadowCamera.z);
        }

        // Cache settings once per frame
        ModernOptSettings.refresh();
        if (!ModernOptSettings.enabled()) {
            resetHistory();
            return;
        }

        // Fast settings cache - only refresh when actually changed
        cacheSettings();

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

        int[] size = RenderScaleMath.targetSize(windowWidth, windowHeight, cachedRenderScale,
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
        boolean settingsChanged = requested != lastUpscaler
                || cachedRenderScale != lastUpscalerPercent
                || cachedSsaa != lastUpscalerSsaa;
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

        // Initialize MSR history targets if needed
        if (cachedMsrEnabled) {
            ensureMSR2HistoryTargets(size[0], size[1]);
            msr2ThisFrame = true;
            msrWanted = false;
        }

        scaled.setLinearFilter(requested != UpscalerType.NEAREST);
        msrThisFrame = msrWanted;
        int outputWidth = minecraft.getMainRenderTarget().width;
        int outputHeight = minecraft.getMainRenderTarget().height;
        if (msrWanted && !msrUnsupported) {
            // The temporal history lives in output resolution. Allocate it here: switching to
            // MSR at runtime used to reach reconstructMsr() with null history targets and
            // silently fall back to FSR 1 for the rest of the session.
            ensureHistoryTargets(outputWidth, outputHeight);
        }
        worldActive = true;
        scaled.bindWrite(true);
        realWindowWidth = minecraft.getWindow().getWidth();
        realWindowHeight = minecraft.getWindow().getHeight();
        windowScaled = true;
        wholeWindowActive = cachedScaleWholeWindow
                && (scaled.width < realWindowWidth || scaled.height < realWindowHeight);
        if (wholeWindowActive) {
            // The scaled target *is* this frame's target; one blit at the end of Minecraft#run
            // puts it on the window. Linear filtering keeps the upscale smooth.
            scaled.setLinearFilter(true);
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
        cachedMsrEnabled = ModernOptSettings.msr2Enabled() || (cachedUpscaler == UpscalerType.MSR2);
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

        // Capture camera pose for both MSR (reprojection + jitter) and MSR2 (reprojection only).
        // MSR does not apply sub-pixel jitter, but it still needs the view/projection/camera state
        // so its temporal pass can reproject history pixels to the previous frame.
        tempQuat.set(camera.rotation()).conjugate();
        currentView.set(tempQuat);
        currentProjection.set(projection);
        currentCameraX = camera.getPosition().x;
        currentCameraY = camera.getPosition().y;
        currentCameraZ = camera.getPosition().z;

        if (!msrThisFrame) {
            // MSR path: pose captured, no jitter applied
            poseCaptured = true;
            return projection;
        }

        if (!isUsableProjection(projection)) {
            resetHistory();
            if (!loggedProjectionGuard) {
                loggedProjectionGuard = true;
                ModernOPT.LOG.debug("[ModernOPT] skipping MSR on a degenerate projection matrix (first frame)");
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
     * itself should stay at native resolution. Previously only MSR stopped here; MSR ran
     * after the hand and therefore read an already-cleared (all-sky) depth buffer.
     */
    public static void finishBeforeHand() {
        if (!worldActive || scaled == null) {
            return;
        }
        // Finish for every upscaler, not just the temporal ones. The hand is rendered into
        // whatever target is bound right after this point; letting it run before the
        // reconstruction would draw it into the scaled target and then overwrite it with the
        // upscaled world (the hand simply disappeared in FSR 1 / bilinear / off modes).
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
        GpuProfiler.end(GpuProfiler.HUD);
        RenderTarget hud = hudTarget;
        RenderTarget main = minecraft.getMainRenderTarget();
        if (hud == null || main == null) {
            return;
        }
        main.bindWrite(true);
        GlStateManager._enableBlend();
        GlStateManager._blendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA,
                GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
        hud.blitToScreen(main.width, main.height, false);
        GlStateManager._disableBlend();
    }

    /** True while the whole frame (world + HUD) is being rendered into the scaled target. */
    public static boolean wholeWindowActive() {
        return wholeWindowActive;
    }

    /** The scaled world target, or {@code null} while scaling is inactive. */
    public static RenderTarget scaledTarget() {
        return scaled;
    }

    // ------------------------------------------------------------------
    // Shadow map hold (update every N frames)
    // ------------------------------------------------------------------

    private static int shadowHoldFrames;
    private static boolean shadowHoldHaveMap;
    private static int shadowHoldFramebuffer = -1;
    private static double shadowHoldCameraX;
    private static double shadowHoldCameraY;
    private static double shadowHoldCameraZ;
    private static boolean shadowHoldForceDraw;

    /** Further than this from the drawn frame's camera forces a rebuild (in blocks, squared). */
    private static final double SHADOW_HOLD_MOVE_LIMIT_SQ = 2.25;

    /** Whether the shadow map has to be drawn on this frame. */
    public static boolean shadowHoldShouldDraw(int interval) {
        return !shadowHoldHaveMap || interval <= 1 || shadowHoldForceDraw
                || shadowHoldFrames >= interval - 1;
    }

    /** Camera position of the last frame that actually drew the map. */
    public static void shadowHoldSetCamera(double x, double y, double z) {
        shadowHoldCameraX = x;
        shadowHoldCameraY = y;
        shadowHoldCameraZ = z;
    }

    /**
     * Forces a rebuild when the camera moved far enough that the held shadow frustum would no
     * longer cover the view, which shows up as shadows disappearing or being clipped at speed.
     */
    public static void shadowHoldCheckCamera(double x, double y, double z) {
        if (!shadowHoldHaveMap || ModernOptSettings.shadowUpdateInterval() <= 1) {
            return;
        }
        double dx = x - shadowHoldCameraX;
        double dy = y - shadowHoldCameraY;
        double dz = z - shadowHoldCameraZ;
        if (dx * dx + dy * dy + dz * dz > SHADOW_HOLD_MOVE_LIMIT_SQ) {
            shadowHoldForceDraw = true;
        }
    }

    /** Marks a frame that skipped the shadow pass. */
    public static void shadowHoldNoteSkipped() {
        shadowHoldFrames++;
    }

    /** Marks a frame that drew the shadow map. */
    public static void shadowHoldNoteDrawn() {
        shadowHoldFrames = 0;
        shadowHoldHaveMap = true;
        shadowHoldForceDraw = false;
    }

    /** GL id of the shadow framebuffer the pack samples, or -1 when unknown. */
    public static int shadowHoldFramebuffer() {
        return shadowHoldFramebuffer;
    }

    public static void shadowHoldSetFramebuffer(int framebuffer) {
        shadowHoldFramebuffer = framebuffer;
    }

    /**
     * Presents a whole-window-scaled frame and disarms the mode.
     *
     * <p>Called at the very end of the GUI pass: the scaled target holds the complete frame
     * (world, hand, HUD), so it is blitted into the real main target with linear filtering, and
     * vanilla's own blit to the window runs unchanged afterwards. Doing it here instead of taking
     * over {@code Minecraft#run} keeps the main loop untouched.</p>
     */
    public static void presentWholeWindow(Minecraft minecraft) {
        if (!wholeWindowActive) {
            return;
        }
        wholeWindowActive = false;
        windowScaled = false;
        ScaledRenderTarget frame = scaled;
        if (frame == null) {
            return;
        }
        RenderTarget main = minecraft.getMainRenderTarget();
        if (main == null) {
            return;
        }
        main.bindWrite(true);
        frame.blitToScreen(main.width, main.height, false);
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
        if (!worldActive || scaled == null) {
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
            GpuProfiler.end(GpuProfiler.UPSCALE);
            return;
        }

        worldActive = false;
        windowScaled = false;
        msrThisFrame = false;
        poseCaptured = false;

        // Handle MSR2
        if (msr2ThisFrame) {
            msr2ActiveThisFrame = true;
            msr2ThisFrame = false;
            // MSR doesn't use jitter, but its temporal pass still needs the camera pose
            // captured above (currentView / currentProjection / currentCamera*).
            jittered = false; // MSR does not modify the projection matrix
        }

        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.getMainRenderTarget();
        if (!ensureShaders(minecraft)) {
            restoreProjection(jittered);
            main.bindWrite(true);
            GpuProfiler.end(GpuProfiler.UPSCALE);
            return;
        }

        try {
            scaled.resolveMsaa();
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
                    reconstructSpatial(main);
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
                        reconstructSpatial(main);
                    } catch (Throwable fallbackFailure) {
                        ModernOPT.LOG.error("[ModernOPT] FSR 1 fallback after MSR failure also failed", fallbackFailure);
                    }
                }
                msr2ActiveThisFrame = false;
            } else {
                reconstructSpatial(main);
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
            ModernOPT.LOG.info("[ModernOPT] presenting {}x{} world image at {}x{} (MSR)",
                    sourceWidth, sourceHeight, outWidth, outHeight);
        }
    }

    /** Spatial reconstruction used by every non-temporal mode, and by the MSR fallback. */
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
                && (upscaler == UpscalerType.FSR1 || upscaler == UpscalerType.MSR);

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
     * internal resolution, and vanilla's present blit upscales it to the window. No extra copy.
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
        int[] size = RenderScaleMath.targetSize(windowWidth, windowHeight, cachedRenderScale,
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
        if (!wholeWindowActive) {
            RenderTarget main = minecraft.getMainRenderTarget();
            int windowWidth = minecraft.getWindow().getWidth();
            int windowHeight = minecraft.getWindow().getHeight();
            if (main != null && windowWidth > 0 && windowHeight > 0
                    && (main.width != windowWidth || main.height != windowHeight)) {
                main.resize(windowWidth, windowHeight, Minecraft.ON_OSX);
                main.setFilterMode(org.lwjgl.opengl.GL11C.GL_NEAREST);
                ModernOPT.LOG.info("[ModernOPT] restored the main target to {}x{}",
                        windowWidth, windowHeight);
            }
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
        RenderTarget main = minecraft.getMainRenderTarget();
        boolean smallMain = main != null && realWindowWidth > 0 && realWindowHeight > 0
                && (main.width != realWindowWidth || main.height != realWindowHeight);
        // A frame can end without the world pass having run (loading screen, menu, a mod cancelling
        // renderLevel). The main target is still small from whole-window mode then, so present it
        // anyway and always drop the window override - otherwise the GUI is laid out for the wrong
        // resolution and only a corner of the frame reaches the window.
        wholeWindowActive = false;
        windowScaled = false;
        if (!smallMain) {
            return;
        }
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
            msrComputeProgram = Msr2TemporalCompute.load(resources);
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
     * TEMPORARY DIAGNOSTIC: one-line snapshot of the GL viewport and the framebuffers involved in
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
        return "window=" + window + " realWindow=" + realWindowWidth + "x" + realWindowHeight
                + " override=" + windowWidthOverride() + "x" + windowHeightOverride()
                + " main=" + main
                + " scaled=" + (scaled == null ? "null" : scaled.width + "x" + scaled.height)
                + " scaledFbo=" + (scaled == null ? -1 : scaled.fboId())
                + " drawFbo=" + drawFbo
                + " viewport=" + vp[0] + "," + vp[1] + " " + vp[2] + "x" + vp[3]
                + " active=" + worldActive;
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

        if (width != realWindowWidth || height != realWindowHeight) {
            return false;
        }

        GlStateManager._viewport(0, 0, target.viewWidth, target.viewHeight);
        return true;
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
        ScaledRenderTarget scaledTarget = scaled;
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
            if (scaledTarget == null
                    || (framebuffer != scaledTarget.fboId()
                    && framebuffer != scaledTarget.activeFramebufferId())) {
                return;
            }
            frameWidth = scaledTarget.viewWidth;
            frameHeight = scaledTarget.viewHeight;
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
        worldActive = false;
        msrThisFrame = false;
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
        if (msr2History != null) {
            msr2History.destroy();
            msr2History = null;
        }
    }

    // ==================== MSR2 METHODS ====================

    private static void resetMsr2History() {
        msrHistoryValid = false;
        msrFrameIndex = 0;
        if (computeAvailable) {
            // A destroyed/invalidated history texture must not stay attached to image units.
            Msr2TemporalCompute.unbindImage(0, GL30C.GL_R11F_G11F_B10F);
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

    

    private static void reconstructMsr2(RenderTarget main) {
        ShaderLibrary library = shaders;
        if (library == null || !library.hasMsr()) {
            throw new IllegalStateException("MSR shaders not available");
        }
        if (msr2History == null) {
            throw new IllegalStateException("MSR history targets not allocated");
        }
        if (msr2History.getWidth() != scaled.width || msr2History.getHeight() != scaled.height) {
            // A resize slipped through beginWorldFrame (Alt+Enter, F11, window drag): recreate
            // before anything samples a stale ping-pong pair.
            ensureMSR2HistoryTargets(scaled.width, scaled.height);
        }

        int outWidth = main.width;
        int outHeight = main.height;
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
            msr2History.swap();
        }

        int temporalColor = temporalWorthIt ? msr2History.getReadColorTexture() : sourceTexture;

        // ===== SPATIAL PASS (EASU + CAS) - always a fragment shader =====
        ShaderLibrary.beginPassState();
        try {
            // The final pass writes the real main target, so alpha must be written as well;
            // beginPassState() disables alpha writes for the history passes.
            GlStateManager._colorMask(true, true, true, true);

            // Native resolution viewport and the real game framebuffer. main.bindWrite() goes
            // through GlStateManager, which now agrees with the FBO actually bound because
            // MSR2HistoryTarget also uses GlStateManager.
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
        Msr2TemporalCompute compute = msrComputeProgram;
        if (compute == null) {
            throw new IllegalStateException("MSR compute program is not loaded");
        }

        int historyColorRead = msr2History.getReadColorTexture();
        int historyDepthRead = msr2History.getReadDepthTexture();
        int historyColorWrite = msr2History.getWriteColorTexture();
        int historyDepthWrite = msr2History.getWriteDepthTexture();

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

            msr2History.bindWriteForCompute();
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

            Msr2TemporalCompute.unbindImage(0, GL30C.GL_R11F_G11F_B10F);
            Msr2TemporalCompute.unbindImage(1, GL30C.GL_R16UI);
            compute.unbind();
        } finally {
            GlStateManager._activeTexture(previousActive);
        }
    }

    /** Fragment-shader fallback for GPUs without a usable GL 4.3 compute program. */
    private static void runFragmentTemporal(ShaderLibrary library, int sourceWidth, int sourceHeight,
            int outWidth, int outHeight, int sourceTexture, int depthTexture) {
        int historyColorRead = msr2History.getReadColorTexture();
        int historyDepthRead = msr2History.getReadDepthTexture();

        ShaderLibrary.beginPassState();
        try {
            // Both MRT attachments (colour + R16UI depth) are written by this pass.
            GlStateManager._colorMask(true, true, true, true);
            msr2History.bindWrite();

            library.msr2Temporal.setSampler("CurrentColor", sourceTexture);
            library.msr2Temporal.setSampler("CurrentDepth", depthTexture);
            library.msr2Temporal.setSampler("HistoryColor", historyColorRead);
            library.msr2Temporal.setSampler("HistoryDepth", historyDepthRead);

            setUniform2f(library.msr2Temporal, "InputSize", (float) sourceWidth, (float) sourceHeight);
            setUniform2f(library.msr2Temporal, "Jitter", 0.0F, 0.0F);
            setUniform1f(library.msr2Temporal, "VarianceScale", (float) cachedMsrVarianceScale);
            setUniform1f(library.msr2Temporal, "DepthThreshold", (float) cachedMsrDepthThreshold);
            setUniform1f(library.msr2Temporal, "HistoryWeight", (float) cachedMsrHistoryWeight);

            tempVec3.set((float) (currentCameraX - previousCameraX),
                    (float) (currentCameraY - previousCameraY),
                    (float) (currentCameraZ - previousCameraZ));
            setUniform3f(library.msr2Temporal, "CameraDelta", tempVec3.x, tempVec3.y, tempVec3.z);
            setUniformMatrix4f(library.msr2Temporal, "ViewRotation", currentView);
            setUniformMatrix4f(library.msr2Temporal, "PrevViewRotation", previousView);
            setUniformMatrix4f(library.msr2Temporal, "InvProjection", inverseProjection);
            setUniformMatrix4f(library.msr2Temporal, "PrevProjection", previousProjection);
            setUniform1f(library.msr2Temporal, "HistoryValid", msrHistoryValid ? 1.0F : 0.0F);
            setUniform1f(library.msr2Temporal, "DepthValid", depthTexture != 0 ? 1.0F : 0.0F);

            library.msr2Temporal.apply();
            drawFullscreenQuad();
            library.msr2Temporal.clear();
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
            Msr2TemporalCompute.unbindImage(0, GL30C.GL_R11F_G11F_B10F);
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







