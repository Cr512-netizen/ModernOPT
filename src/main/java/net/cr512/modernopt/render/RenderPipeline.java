package net.cr512.modernopt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.lwjgl.opengl.GL11C;
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
    private static boolean fsr2SpatialRequested;
    private static boolean fsr2SpatialThisFrame;
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

    private static final Matrix4f currentProjection = new Matrix4f();
    private static final Matrix4f previousProjection = new Matrix4f();
    private static final Matrix4f currentView = new Matrix4f();
    private static final Matrix4f previousView = new Matrix4f();
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
    private static int screenshotTextureUnit = -1;
    private static int screenshotTextureId;

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
        if (requested == UpscalerType.FSR2) {
            if (!fsr2ActiveThisFrame) {
                return ModernOptSettings.ssaa() ? "FSR 2 off (SSAA)" : "FSR 2 off (FSR 1)";
            }
            if (fsr2SpatialThisFrame) {
                return "FSR 2 (spatial)";
            }
            if (!fsr2DepthValidThisFrame) {
                return "FSR 2 (no depth)";
            }
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
        fsr2SpatialRequested = false;
        fsr2SpatialThisFrame = false;
        poseCaptured = false;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.noRender) {
            resetHistory();
            return;
        }

        ModernOptSettings.refresh();
        if (!ModernOptSettings.enabled()) {
            resetHistory();
            return;
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
            lastUpscaler = null;
        }

        UpscalerType requested = ModernOptSettings.upscaler();
        boolean iris = IrisCompat.isShaderPackInUse();
        irisThisFrame = iris;
        if (iris != lastIris) {
            lastIris = iris;
            resetHistory();
            if (iris) {
                // Temporal history is never used under a shader pack. Free the two full-size
                // RGBA16F targets instead of keeping them idle for the whole session.
                destroyHistoryTargets();
            }
        }
        boolean fsr2Wanted = requested == UpscalerType.FSR2 && !ModernOptSettings.ssaa();
        boolean fsr2Spatial = fsr2Wanted && iris;
        fsr2SpatialRequested = fsr2Spatial;
        if (fsr2Spatial && !loggedFsr2Iris) {
            loggedFsr2Iris = true;
            ModernOPT.LOG.info("[ModernOPT] FSR 2 spatial mode with the Iris shader pack: the pack owns "
                    + "sub-pixel jitter, so temporal accumulation is disabled to avoid blurring; using EASU + RCAS");
        }
        if (fsr2Wanted && ModernOptSettings.msaa().samples() > 1 && !loggedFsr2Msaa) {
            loggedFsr2Msaa = true;
            ModernOPT.LOG.info("[ModernOPT] MSAA is off in FSR 2 mode (the temporal pass needs a single sampled depth buffer)");
        }

        int samples = ModernOptSettings.msaa().samples();
        if (samples > 1) {
            if (isFabulous(minecraft)) {
                samples = 1;
                if (!loggedFabulousSkip) {
                    loggedFabulousSkip = true;
                    ModernOPT.LOG.info("[ModernOPT] Fabulous graphics mode is on, MSAA is disabled (it needs a single sampled depth buffer)");
                }
            } else if (iris) {
                // A shader pack builds its own gbuffer and depth targets and resolves them
                // itself; a second multisampled pass on top of that would be useless and can
                // break depth sampling. Render scaling and the upscaler still apply.
                samples = 1;
                logIrisOnce("MSAA is off while an Iris shader pack is active");
            }
        }
        if (fsr2Wanted) {
            samples = 1;
        }

        if (iris) {
            // Iris sizes its gbuffer targets from Minecraft#getMainRenderTarget(), so the
            // redirected target below scales the whole shader pipeline, not just the world pass.
            logIrisOnce("shader pack detected, scaling the Iris pipeline as well");
        }

        if (fsr2Wanted && (!ensureShaders(minecraft) || shaders == null
                || (!fsr2Spatial && !shaders.hasTemporal()))) {
            // Do not render into a target that cannot be presented. The whole shader library
            // failed (or only the temporal pass did); FSR 2 falls back to FSR 1 below, and a
            // broken library leaves vanilla rendering untouched for this session. With an Iris
            // pack the spatial path only needs EASU and RCAS, so a missing temporal shader is
            // not a reason to fall back.
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

        int[] size = RenderScaleMath.targetSize(windowWidth, windowHeight, ModernOptSettings.renderScalePercent(),
                ModernOptSettings.ssaa(), RenderSystem.maxSupportedTextureSize());

        boolean useful = size[0] != windowWidth || size[1] != windowHeight || samples > 1
                || ModernOptSettings.fxaa() || fsr2Wanted;
        if (!useful) {
            resetHistory();
            rememberUpscalerSettings(requested);
            return;
        }

        ensureScaledTarget(size[0], size[1], samples);
        if (scaled == null) {
            resetHistory();
            return;
        }

        if (fsr2Wanted && !fsr2Spatial) {
            try {
                ensureHistoryTargets(minecraft.getMainRenderTarget().width, minecraft.getMainRenderTarget().height);
            } catch (Throwable throwable) {
                fsr2Unsupported = true;
                fsr2Wanted = false;
                resetHistory();
                if (!loggedFsr2Fallback) {
                    loggedFsr2Fallback = true;
                    ModernOPT.LOG.error("[ModernOPT] cannot allocate FSR 2 history targets, falling back to FSR 1",
                            throwable);
                }
            }
        }

        boolean settingsChanged = requested != lastUpscaler
                || ModernOptSettings.renderScalePercent() != lastUpscalerPercent
                || ModernOptSettings.ssaa() != lastUpscalerSsaa;
        rememberUpscalerSettings(requested);
        if (!fsr2Wanted) {
            resetHistory();
        } else if (settingsChanged) {
            resetHistory();
        }

        scaled.setLinearFilter(ModernOptSettings.upscaler() != UpscalerType.NEAREST);
        fsr2ThisFrame = fsr2Wanted;
        int outputWidth = minecraft.getMainRenderTarget().width;
        int outputHeight = minecraft.getMainRenderTarget().height;
        worldActive = true;
        scaled.bindWrite(true);

        if (fsr2ThisFrame && !loggedFsr2Active) {
            loggedFsr2Active = true;
            ModernOPT.LOG.info("[ModernOPT] FSR 2 {} active ({}x{} -> {}x{})",
                    fsr2Spatial ? "spatial upscaling" : "temporal upscaling (camera-reconstructed motion)",
                    scaled.width, scaled.height, outputWidth, outputHeight);
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

        if (fsr2SpatialRequested) {
            // Iris packs jitter their own gbuffers and resolve them in their TAA pass. ModernOPT
            // therefore uses the spatial EASU + RCAS path and must not touch the projection.
            return projection;
        }

        Quaternionf rotation = camera.rotation().conjugate(new Quaternionf());
        currentView.set(new Matrix4f().rotation(rotation));
        currentProjection.set(projection);
        currentCameraX = camera.getPosition().x;
        currentCameraY = camera.getPosition().y;
        currentCameraZ = camera.getPosition().z;

        if (!isUsableProjection(projection)) {
            // The first frame after a world load can still have FOV 0, which makes the
            // perspective matrix degenerate (+Inf entries). Do not jitter or reconstruct
            // that frame; it is not a pass failure, so no error is logged.
            resetHistory();
            if (!loggedProjectionGuard) {
                loggedProjectionGuard = true;
                ModernOPT.LOG.debug("[ModernOPT] skipping FSR 2 on a degenerate projection matrix (first frame)");
            }
            return projection;
        }

        if (irisThisFrame) {
            // Defensive guard: a temporal FSR 2 frame must never run against a shader pack's
            // already-resolved output with zero jitter. Report it as spatial instead of
            // accumulating a blurred history. This should be unreachable because
            // beginWorldFrame selects the spatial path whenever a pack is active.
            jitterX = 0.0F;
            jitterY = 0.0F;
            return projection;
        }

        int index = jitterIndex;
        jitterIndex = (jitterIndex + 1) & 15;
        float offsetX = halton(index + 1, 2) - 0.5F;
        float offsetY = halton(index + 1, 3) - 0.5F;
        jitterX = 2.0F * offsetX / scaled.width;
        jitterY = 2.0F * offsetY / scaled.height;

        Matrix4f jittered = new Matrix4f(projection);
        // clip.x += jitter * clip.w is equivalent to row0 += jitter * row3; for a perspective
        // matrix row3.z is -1, hence the subtraction of the z coefficients.
        jittered.m20(jittered.m20() - jitterX);
        jittered.m21(jittered.m21() - jitterY);
        RenderSystem.setProjectionMatrix(jittered, VertexSorting.DISTANCE_TO_ORIGIN);
        poseCaptured = true;
        return jittered;
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
        boolean spatial = fsr2ThisFrame && !temporal && fsr2SpatialRequested;
        boolean jittered = poseCaptured;
        fsr2ActiveThisFrame = temporal || spatial;
        fsr2SpatialThisFrame = spatial;
        worldActive = false;
        fsr2ThisFrame = false;
        poseCaptured = false;

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
            // Iris' final composite is a fullscreen colour pass and does not write the scaled
            // target's depth attachment. The scene depth lives in the pack's gbuffer depth.
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

        Matrix4f inverseProjection = new Matrix4f();
        currentProjection.invert(inverseProjection);
        if (!inverseProjection.isFinite()) {
            throw new IllegalStateException("projection matrix is not invertible");
        }

        boolean useHistory = historyValid;
        double deltaX = currentCameraX - previousCameraX;
        double deltaY = currentCameraY - previousCameraY;
        double deltaZ = currentCameraZ - previousCameraZ;
        if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ > 64.0 * 64.0) {
            // Teleport or dimension change: do not smear the old image across the new position.
            useHistory = false;
        }
        Vector3f cameraDelta = new Vector3f((float) deltaX, (float) deltaY, (float) deltaZ);

        TextureTarget easu = ensureIntermediates(outWidth, outHeight, true);
        float sharpness = ModernOptSettings.fsrSharpness() / 100.0F;
        boolean fxaa = ModernOptSettings.fxaa();

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
                    cameraDelta.x, cameraDelta.y, cameraDelta.z,
                    jitterX, jitterY, previousJitterX, previousJitterY,
                    useHistory, depthValid, 0.10F, 0.60F, 0.05F);

            // The write target is now the newest history; ping-pong them for the next frame.
            historyFront = write;
            historyBack = read;

            if (fxaa) {
                TextureTarget post = ensureIntermediates(outWidth, outHeight, false);
                post.bindWrite(true);
                ShaderLibrary.draw(library.rcas, write.getColorTextureId(), outWidth, outHeight,
                        outWidth, outHeight, sharpness, 0.0F);
                main.bindWrite(true);
                ShaderLibrary.draw(library.fxaa, post.getColorTextureId(), outWidth, outHeight,
                        outWidth, outHeight, 0.0F, 0.0F);
            } else {
                main.bindWrite(true);
                ShaderLibrary.draw(library.rcas, write.getColorTextureId(), outWidth, outHeight,
                        outWidth, outHeight, sharpness, 0.0F);
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

        float softness = ModernOptSettings.softness() / 100.0F;
        float sharpness = ModernOptSettings.fsrSharpness() / 100.0F;
        boolean fxaa = ModernOptSettings.fxaa();
        ShaderLibrary library = shaders;
        UpscalerType upscaler = ModernOptSettings.upscaler();
        boolean fsr1Path = !ModernOptSettings.ssaa()
                && (upscaler == UpscalerType.FSR1 || upscaler == UpscalerType.FSR2);

        ShaderLibrary.beginPassState();
        try {
            if (fsr1Path) {
                boolean oneToOne = sourceWidth == outWidth && sourceHeight == outHeight;
                if (oneToOne) {
                    // EASU is an upscaler. At 1:1 its reconstruction kernel only softens the
                    // pack's already resolved image, which is exactly the blur seen with Iris
                    // packs at 100% render scale. Copy the image exactly and keep RCAS (the
                    // user's sharpening setting) as the only optional filter.
                    if (fxaa) {
                        TextureTarget post = ensureIntermediates(outWidth, outHeight, false);
                        post.bindWrite(true);
                        ShaderLibrary.draw(library.nearest, sourceTexture, sourceWidth, sourceHeight,
                                outWidth, outHeight, 0.0F, 0.0F);
                        main.bindWrite(true);
                        ShaderLibrary.draw(library.fxaa, post.getColorTextureId(), outWidth, outHeight,
                                outWidth, outHeight, 0.0F, 0.0F);
                    } else if (sharpness > 0.001F) {
                        main.bindWrite(true);
                        ShaderLibrary.draw(library.rcas, sourceTexture, sourceWidth, sourceHeight,
                                outWidth, outHeight, sharpness, 0.0F);
                    } else {
                        main.bindWrite(true);
                        ShaderLibrary.draw(library.nearest, sourceTexture, sourceWidth, sourceHeight,
                                outWidth, outHeight, 0.0F, 0.0F);
                    }
                } else {
                    TextureTarget easu = ensureIntermediates(outWidth, outHeight, true);
                    easu.bindWrite(true);
                    ShaderLibrary.draw(library.easu, sourceTexture, sourceWidth, sourceHeight, outWidth, outHeight, 0.0F, 0.0F);

                    if (fxaa) {
                        TextureTarget post = ensureIntermediates(outWidth, outHeight, false);
                        post.bindWrite(true);
                        ShaderLibrary.draw(library.rcas, easu.getColorTextureId(), outWidth, outHeight, outWidth, outHeight, sharpness, 0.0F);
                        main.bindWrite(true);
                        ShaderLibrary.draw(library.fxaa, post.getColorTextureId(), outWidth, outHeight, outWidth, outHeight, 0.0F, 0.0F);
                    } else {
                        main.bindWrite(true);
                        ShaderLibrary.draw(library.rcas, easu.getColorTextureId(), outWidth, outHeight, outWidth, outHeight, sharpness, 0.0F);
                    }
                }
            } else {
                ShaderInstance filter = ModernOptSettings.ssaa() ? library.box : filterFor(library, upscaler);
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
            if (fsr2SpatialThisFrame) {
                ModernOPT.LOG.info("[ModernOPT] presenting {}x{} world image at {}x{} (FSR 2 spatial)",
                        sourceWidth, sourceHeight, outWidth, outHeight);
            } else {
                ModernOPT.LOG.info("[ModernOPT] presenting {}x{} world image at {}x{} (samples={})",
                        sourceWidth, sourceHeight, outWidth, outHeight, scaled.activeSamples());
            }
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
     * <p>Only a viewport that matches the window size is stale. Other sizes belong to the pass
     * that is setting them up: Iris sets the shadow map viewport (for example 2048x2048) before
     * it binds the shadow framebuffer, while the scaled world target is still current. Clamping
     * that request to the scaled size made the shadow map render into a small corner and removed
     * the shadows at every scale below 100%.</p>
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

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return false;
        }
        if (width != minecraft.getWindow().getWidth() || height != minecraft.getWindow().getHeight()) {
            return false;
        }

        GlStateManager._viewport(0, 0, target.viewWidth, target.viewHeight);
        return true;
    }

    /**
     * Saves the texture unit that {@link Screenshot#takeScreenshot} is about to change.
     *
     * <p>Vanilla binds the main colour texture to the currently active unit and never restores
     * it. Sodium and Iris cache which texture they last bound per unit, so leaving the screen
     * image there desynchronises their cache: the next world frame keeps sampling the screen
     * instead of the block atlas, and the HUD is smeared over every block. Saving and restoring
     * the exact unit and binding makes screenshots side-effect free.</p>
     *
     * <p>The previous implementation moved the read to unit 11 to avoid touching world units.
     * That is unsafe: {@code GlStateManager} caches bindings per unit, and Iris/Kappa may bind
     * textures outside that cache. The cached unit 11 binding can then disagree with the real
     * GL binding, and {@code takeScreenshot} downloads whatever texture is actually there (the
     * font atlas, visible as glyphs tiled over the saved image) instead of the world colour.
     * Reading on the current unit and restoring it keeps the cache and GL state in sync.</p>
     */
    public static void saveTextureStateBeforeScreenshot() {
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        screenshotTextureUnit = GlStateManager._getActiveTexture();
        screenshotTextureId = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
    }

    /** Restores the unit and binding saved by the method above. */
    public static void restoreTextureStateAfterScreenshot() {
        if (!RenderSystem.isOnRenderThread() || screenshotTextureUnit < 0) {
            return;
        }
        int unit = screenshotTextureUnit;
        int texture = screenshotTextureId;
        screenshotTextureUnit = -1;
        screenshotTextureId = 0;

        GlStateManager._activeTexture(unit);
        GlStateManager._bindTexture(texture);
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
    }
}
