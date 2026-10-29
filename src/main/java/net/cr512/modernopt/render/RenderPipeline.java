package net.cr512.modernopt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
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
    private static ShaderInstance msrTemporalShader;
    private static ShaderInstance msrSpatialShader;
    private static ShaderInstance msrTemporalComputeShader;
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

    // Compute shader support (GL 4.3+)
    private static boolean computeAvailable = false;
    private static MotionVectorCollector motionVectorCollector;

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

    /** Called at the head of {@code GameRenderer#renderLevel}. */
    public static void beginWorldFrame() {
        worldActive = false;
        fsr2ThisFrame = false;
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

        int windowWidth = minecraft.getWindow().getWidth();
        int windowHeight = minecraft.getWindow().getHeight();
        if (windowWidth <= 0 || windowHeight <= 0) {
            resetHistory();
            return;
        }

        if (minecraft.level != lastLevel) {
            lastLevel = minecraft.level;
            resetHistory();
            lastUpscaler = null;
        }

        UpscalerType requested = cachedUpscaler;
        boolean iris = IrisCompat.isShaderPackInUse();
        irisThisFrame = iris;
        if (iris != lastIris) {
            lastIris = iris;
            resetHistory();
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

        // Initialize MSR history targets if needed
        if (cachedMsrEnabled) {
            ensureMsrHistoryTargets(minecraft.getMainRenderTarget().width, minecraft.getMainRenderTarget().height);
            msrThisFrame = true;
            fsr2Wanted = false;
        }

        boolean settingsChanged = requested != lastUpscaler
                || cachedRenderScale != lastUpscalerPercent
                || cachedSsaa != lastUpscalerSsaa;
        rememberUpscalerSettings(requested);
        if (!fsr2Wanted) {
            resetHistory();
        } else if (settingsChanged) {
            resetHistory();
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
        cachedMsrEnabled = ModernOptSettings.msrEnabled();
        cachedMsrVarianceScale = ModernOptSettings.msrVarianceScale();
        cachedMsrEdgeAware = ModernOptSettings.msrEdgeAware();
        cachedMsrSharpness = ModernOptSettings.msrSharpness();
        cachedMsrDepthThreshold = ModernOptSettings.msrDepthThreshold();
        cachedMsrHistoryWeight = ModernOptSettings.msrHistoryWeight();
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
        if (!fsr2ThisFrame || scaled == null) {
            return projection;
        }

        tempQuat.set(camera.rotation()).conjugate();
        currentView.set(tempQuat);
        currentProjection.set(projection);
        currentCameraX = camera.getPosition().x;
        currentCameraY = camera.getPosition().y;
        currentCameraZ = camera.getPosition().z;

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
     * Called right before the vanilla hand pass. FSR 2 has to finish here: the hand pass clears
     * the depth buffer, which the temporal pass still needs, and the hand itself should stay at
     * native resolution.
     */
    public static void finishBeforeHand() {
        if (!worldActive || scaled == null || !fsr2ThisFrame) {
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
            // MSR doesn't use jitter, so no projection restore needed
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
                    }
                    reconstructSpatial(main);
                }
            } else if (msrActiveThisFrame) {
                try {
                    reconstructMsr(main);
                } catch (Throwable throwable) {
                    msrShaderFailure = true;
                    resetMsrHistory();
                    ModernOPT.LOG.error("[ModernOPT] MSR 2.0 pass failed, falling back to FSR 1", throwable);
                    reconstructSpatial(main);
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
                    useHistory, depthValid, 0.10F, 0.60F, 0.05F);

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

        ShaderLibrary.beginPassState();
        try {
            if (fsr1Path) {
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
            case BILINEAR, FSR1, FSR2 -> library.bilinear;
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

        if (!(minecraft.getResourceManager() instanceof ResourceProvider resources)) {
            shaderFailure = true;
            ModernOPT.LOG.error("[ModernOPT] resource manager is not a ResourceProvider, render scaling disabled");
            return false;
        }

        try {
            shaders = ShaderLibrary.load(resources);
            resetHistory();
            ModernOPT.LOG.info("[ModernOPT] upscaler shaders compiled");
            return true;
        } catch (Throwable throwable) {
            shaderFailure = true;
            ModernOPT.LOG.error("[ModernOPT] upscaler shaders failed to compile, render scaling disabled", throwable);
            return false;
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
        if (msrHistory != null) {
            msrHistory.destroy();
            msrHistory = null;
        }
        if (msrTemporalShader != null) {
            msrTemporalShader.close();
            msrTemporalShader = null;
        }
        if (msrSpatialShader != null) {
            msrSpatialShader.close();
            msrSpatialShader = null;
        }
    }

    // ==================== MSR 2.0 METHODS ====================

    private static void resetMsrHistory() {
        msrHistoryValid = false;
        msrFrameIndex = 0;
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

    private static void ensureMsrShaders(Minecraft minecraft) {
        if (msrShaderFailure) return;
        if (msrTemporalShader != null && msrSpatialShader != null) return;

        if (!(minecraft.getResourceManager() instanceof ResourceProvider resources)) {
            msrShaderFailure = true;
            ModernOPT.LOG.error("[ModernOPT] MSR: resource manager is not a ResourceProvider");
            return;
        }

        try {
            var temporalLoc = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ModernOPT.MOD_ID, "msr_temporal");
            var spatialLoc = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ModernOPT.MOD_ID, "msr_spatial");
            msrTemporalShader = new ShaderInstance(resources, temporalLoc, com.mojang.blaze3d.vertex.DefaultVertexFormat.BLIT_SCREEN);
            msrSpatialShader = new ShaderInstance(resources, spatialLoc, com.mojang.blaze3d.vertex.DefaultVertexFormat.BLIT_SCREEN);

            // Load compute shader if GL 4.3+ available
            if (org.lwjgl.opengl.GL.getCapabilities().OpenGL43) {
                var computeLoc = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ModernOPT.MOD_ID, "msr_temporal_compute");
                msrTemporalComputeShader = new ShaderInstance(resources, computeLoc, com.mojang.blaze3d.vertex.DefaultVertexFormat.BLIT_SCREEN);
                ModernOPT.LOG.info("[ModernOPT] MSR 2.0 compute shader compiled");
            }

            ModernOPT.LOG.info("[ModernOPT] MSR 2.0 shaders compiled");
        } catch (Throwable throwable) {
            msrShaderFailure = true;
            ModernOPT.LOG.error("[ModernOPT] MSR 2.0 shaders failed to compile", throwable);
        }
    }

    private static void reconstructMsr(RenderTarget main) {
        ensureMsrShaders(Minecraft.getInstance());
        if (msrShaderFailure || msrTemporalShader == null || msrSpatialShader == null) {
            throw new IllegalStateException("MSR shaders not available");
        }
        if (msrHistory == null) {
            throw new IllegalStateException("MSR history targets not allocated");
        }

        int outWidth = main.width;
        int outHeight = main.height;
        int sourceWidth = scaled.width;
        int sourceHeight = scaled.height;
        int sourceTexture = scaled.getColorTextureId();
        int depthTexture = scaled.getDepthTextureId();

        // Get ping-pong textures
        int historyColorRead = msrHistory.getReadColorTexture();
        int historyDepthRead = msrHistory.getReadDepthTexture();
        int historyColorWrite = msrHistory.getWriteColorTexture();
        int historyDepthWrite = msrHistory.getWriteDepthTexture();

        if (computeAvailable && motionVectorCollector != null) {
            // ===== COMPUTE SHADER PATH (GL 4.3+) =====
            uploadAndDispatchComputeTemporal(main, outWidth, outHeight, sourceWidth, sourceHeight,
                    sourceTexture, depthTexture, historyColorRead, historyDepthRead,
                    historyColorWrite, historyDepthWrite);
        } else {
            // ===== FRAGMENT SHADER FALLBACK (GL 3.2+) =====
            msrHistory.bindWrite();

            // ===== TEMPORAL PASS =====
            msrTemporalShader.setSampler("CurrentColor", sourceTexture);
            msrTemporalShader.setSampler("HistoryColor", historyColorRead);
            msrTemporalShader.setSampler("HistoryDepth", historyDepthRead);
            msrTemporalShader.setSampler("CurrentDepth", depthTexture);

            msrTemporalShader.getUniform("InputSize").set((float) sourceWidth, (float) sourceHeight);
            msrTemporalShader.getUniform("OutputSize").set((float) outWidth, (float) outHeight);
            msrTemporalShader.getUniform("Jitter").set(0.0f, 0.0f);
            msrTemporalShader.getUniform("VarianceScale").set((float) cachedMsrVarianceScale);
            msrTemporalShader.getUniform("DepthThreshold").set((float) cachedMsrDepthThreshold);
            msrTemporalShader.getUniform("HistoryWeight").set((float) cachedMsrHistoryWeight);

            msrTemporalShader.apply();
            drawFullscreenQuad();
            msrTemporalShader.clear();

            // Swap ping-pong for spatial pass
            msrHistory.swap();
        }

        // ===== SPATIAL PASS (always fragment shader for TBDR compatibility) =====
        main.bindWrite(true);

        // After swap, historyColorWrite becomes the read texture
        int temporalColorRead = msrHistory.getReadColorTexture();

        msrSpatialShader.setSampler("TemporalColor", temporalColorRead);
        msrSpatialShader.setSampler("DepthTexture", depthTexture);

        msrSpatialShader.getUniform("InputSize").set((float) outWidth, (float) outHeight);
        msrSpatialShader.getUniform("OutputSize").set((float) outWidth, (float) outHeight);
        msrSpatialShader.getUniform("Sharpness").set(cachedMsrSharpness / 100.0f);
        msrSpatialShader.getUniform("EdgeAwareStrength").set(cachedMsrEdgeAware ? 1.0f : 0.0f);
        msrSpatialShader.getUniform("DepthEdgeThreshold").set((float) cachedMsrDepthThreshold);

        msrSpatialShader.apply();
        drawFullscreenQuad();
        msrSpatialShader.clear();

        msrHistoryValid = true;
        msrFrameIndex++;
    }

    /**
     * Upload motion vectors and dispatch compute temporal pass.
     */
    private static void uploadAndDispatchComputeTemporal(RenderTarget main, int outWidth, int outHeight,
            int sourceWidth, int sourceHeight, int sourceTexture, int depthTexture,
            int historyColorRead, int historyDepthRead, int historyColorWrite, int historyDepthWrite) {

        // Upload motion vectors to SSBO
        motionVectorCollector.uploadToSsbo();
        motionVectorCollector.bindSsbo(0); // Binding point 0

        // Bind history textures as images for compute shader output
        org.lwjgl.opengl.GL42.glBindImageTexture(0, historyColorWrite, 0, false, 0,
                org.lwjgl.opengl.GL42.GL_WRITE_ONLY, org.lwjgl.opengl.GL30.GL_RGBA32F);
        org.lwjgl.opengl.GL42.glBindImageTexture(1, historyDepthWrite, 0, false, 0,
                org.lwjgl.opengl.GL42.GL_WRITE_ONLY, org.lwjgl.opengl.GL30.GL_R32UI);

        // Use compute shader if available, otherwise fall back to fragment
        if (msrTemporalComputeShader != null) {
            // Use compute shader
            msrTemporalComputeShader.setSampler("CurrentColor", sourceTexture);
            msrTemporalComputeShader.setSampler("HistoryColor", historyColorRead);
            msrTemporalComputeShader.setSampler("HistoryDepth", historyDepthRead);
            msrTemporalComputeShader.setSampler("CurrentDepth", scaled.getDepthTextureId());

            msrTemporalComputeShader.getUniform("InputSize").set((float) sourceWidth, (float) sourceHeight);
            msrTemporalComputeShader.getUniform("OutputSize").set((float) outWidth, (float) outHeight);
            msrTemporalComputeShader.getUniform("Jitter").set(0.0f, 0.0f);
            msrTemporalComputeShader.getUniform("VarianceScale").set((float) cachedMsrVarianceScale);
            msrTemporalComputeShader.getUniform("DepthThreshold").set((float) cachedMsrDepthThreshold);
            msrTemporalComputeShader.getUniform("HistoryWeight").set((float) cachedMsrHistoryWeight);

            msrTemporalComputeShader.apply();

            // Dispatch compute shader
            int groupX = (outWidth + 7) / 8;
            int groupY = (outHeight + 7) / 8;
            org.lwjgl.opengl.GL31.glDispatchCompute(groupX, groupY, 1);
            org.lwjgl.opengl.GL42.glMemoryBarrier(org.lwjgl.opengl.GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

            msrTemporalComputeShader.clear();
        } else {
            // Fallback to fragment shader
            msrHistory.bindWrite();
            msrTemporalShader.setSampler("CurrentColor", sourceTexture);
            msrTemporalShader.setSampler("HistoryColor", historyColorRead);
            msrTemporalShader.setSampler("HistoryDepth", historyDepthRead);
            msrTemporalShader.setSampler("CurrentDepth", depthTexture);
            msrTemporalShader.getUniform("InputSize").set((float) sourceWidth, (float) sourceHeight);
            msrTemporalShader.getUniform("OutputSize").set((float) outWidth, (float) outHeight);
            msrTemporalShader.getUniform("Jitter").set(0.0f, 0.0f);
            msrTemporalShader.getUniform("VarianceScale").set((float) cachedMsrVarianceScale);
            msrTemporalShader.getUniform("DepthThreshold").set((float) cachedMsrDepthThreshold);
            msrTemporalShader.getUniform("HistoryWeight").set((float) cachedMsrHistoryWeight);
            msrTemporalShader.apply();
            drawFullscreenQuad();
            msrTemporalShader.clear();
        }

        // Memory barrier before spatial pass
        org.lwjgl.opengl.GL42.glMemoryBarrier(org.lwjgl.opengl.GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT |
                org.lwjgl.opengl.GL42.GL_TEXTURE_FETCH_BARRIER_BIT);

        // Swap ping-pong for spatial pass
        msrHistory.swap();
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
}





