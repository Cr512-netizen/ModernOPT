package net.cr512.modernopt.config;

/**
 * Per-frame snapshot of the client config. Reading {@code ModConfigSpec} values allocates and
 * validates on every call, so the render thread only ever sees these plain fields.
 */
public final class ModernOptSettings {

    private static volatile boolean enabled = true;
    private static volatile boolean ssaa;
    private static volatile boolean fxaa;
    private static volatile boolean overlay;
    private static volatile boolean logFps = false;
    private static volatile int renderScalePercent = 75;
    private static volatile int softness = 35;
    private static volatile int fsrEasuSharpness = 0;
    private static volatile int fsrRcasSharpness = 25;
    private static volatile UpscalerType upscaler = UpscalerType.FSR1;
    private static volatile MsaaLevel msaa = MsaaLevel.OFF;
    private static volatile int shadowMapResolution = 2048;
    private static volatile int shadowUpdateInterval = 2;
    private static volatile boolean fp16World = false;
    private static volatile int hudScalePercent = 100;
    private static volatile boolean scaleWholeWindow;

    // MSR2 settings
    private static volatile boolean msr2Enabled = false;
    private static volatile double msr2VarianceScale = 2.0;
    private static volatile boolean msr2EdgeAware = true;
    private static volatile int msr2Sharpness = 30;
    private static volatile double msr2DepthThreshold = 0.02;
    private static volatile double msr2HistoryWeight = 0.85;

    // DRS settings
    private static volatile boolean msr2DynamicRes = true;
    private static volatile int msr2TemporalMinFps = 40;
    private static volatile int msr2ScaleStep = 5;
    private static volatile int msr2MinScale = 50;

    private ModernOptSettings() {
    }

    public static void refresh() {
        if (!ModernOptConfig.SPEC.isLoaded()) {
            return;
        }
        enabled = ModernOptConfig.ENABLED.get();
        overlay = ModernOptConfig.SHOW_OVERLAY.get();
        logFps = ModernOptConfig.LOG_FPS.get();
        renderScalePercent = ModernOptConfig.RENDER_SCALE_PERCENT.get();
        upscaler = ModernOptConfig.UPSCALER.get();
        softness = ModernOptConfig.SOFTNESS.get();
        fsrEasuSharpness = ModernOptConfig.FSR_EASU_SHARPNESS.get();
        fsrRcasSharpness = ModernOptConfig.FSR_RCAS_SHARPNESS.get();
        msaa = ModernOptConfig.MSAA.get();
        ssaa = ModernOptConfig.SSAA.get();
        fxaa = ModernOptConfig.FXAA.get();
        shadowMapResolution = ModernOptConfig.SHADOW_MAP_RESOLUTION.get();
        shadowUpdateInterval = ModernOptConfig.SHADOW_UPDATE_INTERVAL.get();
        fp16World = ModernOptConfig.FP16_WORLD.get();
        hudScalePercent = ModernOptConfig.HUD_SCALE_PERCENT.get();
        scaleWholeWindow = ModernOptConfig.SCALE_WHOLE_WINDOW.get();

        // MSR2
        msr2Enabled = ModernOptConfig.MSR2_ENABLED.get();
        msr2VarianceScale = ModernOptConfig.MSR2_VARIANCE_SCALE.get();
        msr2EdgeAware = ModernOptConfig.MSR2_EDGE_AWARE.get();
        msr2Sharpness = ModernOptConfig.MSR2_SHARPNESS.get();
        msr2DepthThreshold = ModernOptConfig.MSR2_DEPTH_THRESHOLD.get();
        msr2HistoryWeight = ModernOptConfig.MSR2_HISTORY_WEIGHT.get();

        // DRS
        msr2DynamicRes = ModernOptConfig.MSR2_DYNAMIC_RES.get();
        msr2TemporalMinFps = ModernOptConfig.MSR2_TEMPORAL_MIN_FPS.get();
        msr2ScaleStep = ModernOptConfig.MSR2_SCALE_STEP.get();
        msr2MinScale = ModernOptConfig.MSR2_MIN_SCALE.get();
    }

    public static boolean enabled() {
        return enabled;
    }

    public static boolean ssaa() {
        return ssaa;
    }

    public static boolean fxaa() {
        return fxaa;
    }

    public static boolean overlay() {
        return overlay;
    }

    public static boolean logFps() {
        return logFps;
    }

    public static int renderScalePercent() {
        return renderScalePercent;
    }

    public static int softness() {
        return softness;
    }

    public static int fsrEasuSharpness() {
        return fsrEasuSharpness;
    }

    public static int fsrRcasSharpness() {
        return fsrRcasSharpness;
    }

    // Deprecated alias for compatibility
    @Deprecated
    public static int fsrSharpness() {
        return fsrRcasSharpness;
    }

    public static UpscalerType upscaler() {
        return upscaler;
    }

    public static MsaaLevel msaa() {
        return msaa;
    }

    /** Requested shadow map resolution in pixels (512, 1024, 2048 or 4096). */
    public static int shadowMapResolution() {
        return shadowMapResolution;
    }

    /** Rebuild the shadow map every N frames (1 = every frame). */
    public static int shadowUpdateInterval() {
        return shadowUpdateInterval;
    }
    /** Render the pre-upscale image into an RGBA16F buffer instead of RGBA8. */
    public static boolean fp16World() {
        return fp16World;
    }

    /** Resolution the HUD is rendered at, in percent of the window (50-100). */
    public static int hudScalePercent() {
        return hudScalePercent;
    }

    /** Scale the whole frame (world + HUD + presentation) instead of only the world target. */
    public static boolean scaleWholeWindow() {
        return scaleWholeWindow;
    }

    // MSR2 getters
    public static boolean msr2Enabled() {
        return msr2Enabled;
    }

    public static double msr2VarianceScale() {
        return msr2VarianceScale;
    }

    public static boolean msr2EdgeAware() {
        return msr2EdgeAware;
    }

    public static int msr2Sharpness() {
        return msr2Sharpness;
    }

    public static double msr2DepthThreshold() {
        return msr2DepthThreshold;
    }

    public static double msr2HistoryWeight() {
        return msr2HistoryWeight;
    }

    // DRS getters
    public static boolean msr2DynamicRes() {
        return msr2DynamicRes;
    }

    public static int msr2TemporalMinFps() {
        return msr2TemporalMinFps;
    }

    public static int msr2ScaleStep() {
        return msr2ScaleStep;
    }

    public static int msr2MinScale() {
        return msr2MinScale;
    }
}
