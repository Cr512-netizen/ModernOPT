package net.cr512.modernopt.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.cr512.modernopt.ModernOPT;
import net.cr512.modernopt.config.ModernOptSettings;
import net.cr512.modernopt.config.UpscalerType;
import net.cr512.modernopt.compat.IrisCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceProvider;

/**
 * Drives the whole render scaling pipeline.
 *
 * <p>Frame flow with the mixins attached:</p>
 * <ol>
 *   <li>{@code GameRenderer#renderLevel} HEAD: bind the scaled target and tell
 *       {@code Minecraft#getMainRenderTarget} to answer with it, so the world, entities,
 *       particles, clouds and post effects all land in the smaller/larger image.</li>
 *   <li>{@code GameRenderer#render} right before {@code Gui#render}: unset that redirect,
 *       resolve MSAA if needed, run the reconstruction filter into the real main target and
 *       bind it again, so the HUD and screens stay at native resolution.</li>
 * </ol>
 */
public final class RenderPipeline {

    private static ScaledRenderTarget scaled;
    private static TextureTarget fsrTarget;
    private static TextureTarget fxaaTarget;
    private static ShaderLibrary shaders;
    private static boolean shaderFailure;
    private static boolean worldActive;
    private static boolean loggedIrisSkip;
    private static boolean loggedFabulousSkip;
    private static int lastWidth = -1;
    private static int lastHeight = -1;

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

    /** The target the world should render into, or {@code null} when vanilla rendering is fine. */
    public static RenderTarget currentWorldTarget() {
        return worldActive ? scaled : null;
    }

    /** Called at the head of {@code GameRenderer#renderLevel}. */
    public static void beginWorldFrame() {
        worldActive = false;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.noRender) {
            return;
        }

        ModernOptSettings.refresh();
        if (!ModernOptSettings.enabled()) {
            return;
        }

        int windowWidth = minecraft.getWindow().getWidth();
        int windowHeight = minecraft.getWindow().getHeight();
        if (windowWidth <= 0 || windowHeight <= 0) {
            return;
        }

        int samples = ModernOptSettings.msaa().samples();
        if (samples > 1) {
            if (isFabulous(minecraft)) {
                samples = 1;
                if (!loggedFabulousSkip) {
                    loggedFabulousSkip = true;
                    ModernOPT.LOG.info("[ModernOPT] Fabulous graphics mode is on, MSAA is disabled (it needs a single sampled depth buffer)");
                }
            } else if (IrisCompat.isShaderPackInUse()) {
                // A shader pack builds its own gbuffer and depth targets and resolves them
                // itself; a second multisampled pass on top of that would be useless and can
                // break depth sampling. Render scaling and the upscaler still apply.
                samples = 1;
                logIrisOnce("MSAA is off while an Iris shader pack is active");
            }
        }

        if (IrisCompat.isShaderPackInUse()) {
            // Iris sizes its gbuffer targets from Minecraft#getMainRenderTarget(), so the
            // redirected target below scales the whole shader pipeline, not just the world pass.
            logIrisOnce("shader pack detected, scaling the Iris pipeline as well");
        }

        int[] size = RenderScaleMath.targetSize(windowWidth, windowHeight, ModernOptSettings.renderScalePercent(),
                ModernOptSettings.ssaa(), RenderSystem.maxSupportedTextureSize());

        boolean useful = size[0] != windowWidth || size[1] != windowHeight || samples > 1 || ModernOptSettings.fxaa();
        if (!useful) {
            return;
        }

        ensureScaledTarget(size[0], size[1], samples);
        if (scaled == null) {
            return;
        }

        scaled.setLinearFilter(ModernOptSettings.upscaler() != UpscalerType.NEAREST);
        scaled.bindWrite(true);
        worldActive = true;
    }

    /** Called right before {@code Gui#render}, i.e. after the world and before the HUD. */
    public static void endWorldFrame() {
        boolean wasActive = worldActive;
        worldActive = false;
        if (!wasActive || scaled == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!ensureShaders(minecraft)) {
            minecraft.getMainRenderTarget().bindWrite(true);
            return;
        }

        try {
            scaled.resolveMsaa();
            reconstruct(minecraft);
        } catch (Throwable throwable) {
            ModernOPT.LOG.error("[ModernOPT] reconstruction failed, falling back to a plain blit", throwable);
            shaderFailure = true;
            minecraft.getMainRenderTarget().bindWrite(true);
        }
    }

    private static void reconstruct(Minecraft minecraft) {
        RenderTarget main = minecraft.getMainRenderTarget();
        int outWidth = main.width;
        int outHeight = main.height;
        int sourceTexture = scaled.getColorTextureId();
        int sourceWidth = scaled.width;
        int sourceHeight = scaled.height;

        float softness = ModernOptSettings.softness() / 100.0F;
        float sharpness = ModernOptSettings.fsrSharpness() / 100.0F;
        boolean fxaa = ModernOptSettings.fxaa();
        ShaderLibrary library = shaders;

        ShaderLibrary.beginPassState();
        try {
            if (ModernOptSettings.upscaler() == UpscalerType.FSR1 && !ModernOptSettings.ssaa()) {
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
            } else {
                ShaderInstance filter = ModernOptSettings.ssaa() ? library.box : filterFor(library, ModernOptSettings.upscaler());
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
            case BILINEAR, FSR1 -> library.bilinear;
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

    private static void ensureScaledTarget(int width, int height, int samples) {
        if (scaled != null && scaled.width == width && scaled.height == height
                && scaled.requestedSamples() == Math.max(1, samples) && scaled.isMultisampled() == (samples > 1)) {
            return;
        }
        if (scaled != null) {
            scaled.destroyBuffers();
            scaled = null;
        }
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

    /** Drops every GL object; called from the client stopping event so nothing leaks on world exit. */
    public static void shutdown() {
        worldActive = false;
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
    }
}
