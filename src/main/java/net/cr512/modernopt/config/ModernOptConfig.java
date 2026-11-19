package net.cr512.modernopt.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client configuration. Values are cached so the render thread never touches
 * {@link ModConfigSpec.ConfigValue#get()} on a hot path.
 */
public final class ModernOptConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue RENDER_SCALE_PERCENT;
    public static final ModConfigSpec.EnumValue<UpscalerType> UPSCALER;
    public static final ModConfigSpec.IntValue SOFTNESS;
    public static final ModConfigSpec.IntValue FSR_EASU_SHARPNESS;
    public static final ModConfigSpec.IntValue FSR_RCAS_SHARPNESS;
    public static final ModConfigSpec.EnumValue<MsaaLevel> MSAA;
    public static final ModConfigSpec.BooleanValue SSAA;
    public static final ModConfigSpec.BooleanValue FXAA;
    public static final ModConfigSpec.BooleanValue FP16_WORLD;
    public static final ModConfigSpec.BooleanValue SHOW_OVERLAY;
    public static final ModConfigSpec.BooleanValue LOG_FPS;

    // Shader pack shadow map
    public static final ModConfigSpec.IntValue SHADOW_MAP_RESOLUTION;
    public static final ModConfigSpec.IntValue SHADOW_UPDATE_INTERVAL;
    public static final ModConfigSpec.IntValue HUD_SCALE_PERCENT;
    public static final ModConfigSpec.BooleanValue SCALE_WHOLE_WINDOW;

    // MSR2 config
    public static final ModConfigSpec.BooleanValue MSR2_ENABLED;
    public static final ModConfigSpec.DoubleValue MSR2_VARIANCE_SCALE;
    public static final ModConfigSpec.BooleanValue MSR2_EDGE_AWARE;
    public static final ModConfigSpec.IntValue MSR2_SHARPNESS;
    public static final ModConfigSpec.DoubleValue MSR2_DEPTH_THRESHOLD;
    public static final ModConfigSpec.DoubleValue MSR2_HISTORY_WEIGHT;
    public static final ModConfigSpec.BooleanValue MSR2_DYNAMIC_RES;
    public static final ModConfigSpec.IntValue MSR2_TEMPORAL_MIN_FPS;
    public static final ModConfigSpec.IntValue MSR2_SCALE_STEP;
    public static final ModConfigSpec.IntValue MSR2_MIN_SCALE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Master switch. When disabled the mod touches nothing.").push("general");
        ENABLED = b.comment("Enable ModernOPT render modifications.").define("enabled", true);
        SHOW_OVERLAY = b.comment("Show the FPS / resolution overlay.").define("showOverlay", false);
        LOG_FPS = b.comment("Log FPS statistics to file every second (avg FPS, ms/frame, worst frame, 1% low, 0.1% low, mod settings).")
                .define("logFps", false);
        b.pop();

        b.comment("Internal world render resolution.").push("renderScale");
        RENDER_SCALE_PERCENT = b.comment("Internal world resolution as a percentage of the window (25-100).")
                .defineInRange("percent", 75, 25, 100);
        UPSCALER = b.comment("Reconstruction filter used to scale back up.")
                .defineEnum("upscaler", UpscalerType.FSR1);
        SOFTNESS = b.comment("SoftScale blur strength, 0 = neutral, 100 = very soft.")
                .defineInRange("softness", 35, 0, 100);
        FSR_EASU_SHARPNESS = b.comment("FSR 1 / MSR EASU sharpening, 0 = off, 100 = strong.")
                .defineInRange("fsrEasuSharpness", 0, 0, 100);
        FSR_RCAS_SHARPNESS = b.comment("FSR 1 / MSR RCAS sharpening, 0 = off, 100 = strong.")
                .defineInRange("fsrRcasSharpness", 25, 0, 100);
        HUD_SCALE_PERCENT = b.comment(
                        "Resolution the HUD and other GUI elements are rendered at, as a percentage of the window.",
                        "100 keeps the interface pixel sharp. Lower values render the HUD at the same reduced",
                        "resolution as the world and upscale it together with the final image, which saves the",
                        "fill rate of the whole GUI pass (the text becomes softer).")
                .defineInRange("hudScalePercent", 100, 50, 100);
        SCALE_WHOLE_WINDOW = b.comment(
                        "Scale the entire frame instead of only the world render target.",
                        "The world, HUD, Iris composites and the final presentation all run at the internal",
                        "resolution and one blit puts the frame on the window. It is the fastest mode, at",
                        "the cost of a softer HUD and lower resolution screenshots. Off by default: the",
                        "world-only mode keeps the interface pixel sharp.")
                .define("scaleWholeWindow", false);
        b.pop();

        b.comment("Shader pack shadow map.").push("shadows");
        SHADOW_MAP_RESOLUTION = b.comment(
                        "Absolute shadow map resolution used by Iris shader packs: 512, 1024, 2048 or 4096.",
                        "The shadow map is independent of the render scale: 50% scale with a 2048 map is valid.",
                        "Lower values cost noticeably less GPU time and VRAM and produce softer, blockier",
                        "shadow edges. Values between the steps are snapped to the nearest one.",
                        "Applied when a world loads or when this value changes (the shader pack is reloaded).")
                .defineInRange("shadowMapResolution", 2048, 512, 4096);
        SHADOW_UPDATE_INTERVAL = b.comment(
                        "Rebuild the shadow map every N frames instead of every frame.",
                        "1 keeps the pack's normal behaviour. 2-4 skip the whole world-from-the-sun pass on",
                        "the frames in between and keep sampling the previous map with the exact matrices",
                        "of the frame that drew it. While the camera and nearby casters are still,",
                        "the map is held longer (up to eight frames); movement or a sun/time jump",
                        "rebuilds it immediately.")
                .defineInRange("updateInterval", 2, 1, 4);
        b.pop();

        b.comment("Anti-aliasing.").push("antialiasing");
        MSAA = b.comment("Multisample anti-aliasing for the world pass.",
                        "Requires the world to be rendered off-screen; ignored in Fabulous mode.")
                .defineEnum("msaa", MsaaLevel.OFF);
        SSAA = b.comment("Render the world at 2x width and height, then downsample.",
                        "Takes priority over render scale and MSAA.")
                .define("ssaa", false);
        FXAA = b.comment("Post-process edge smoothing on the final image.").define("fxaa", false);
        FP16_WORLD = b.comment(
                        "Render the internal (before-upscale) world image into a 16-bit float (RGBA16F) buffer.",
                        "Shader packs produce HDR colours, so this avoids quantising the image before the upscaler",
                        "sees it (less banding in gradients). It doubles the bandwidth of that buffer: measured",
                        "about 1.3 ms per frame at 960x540 with a typical shader pack, so it is off by default.")
                .define("fp16ColorTarget", false);
        b.pop();

        b.comment("MSR2 - experimental temporal upscaler.").push("msr2");
        MSR2_ENABLED = b.comment("Enable MSR2 upscaler (experimental). Overrides other upscalers when enabled.")
                .define("enabled", false);
        MSR2_VARIANCE_SCALE = b.comment("Variance clipping threshold multiplier for temporal pass. Higher = more aggressive history rejection.")
                .defineInRange("varianceScale", 2.0, 0.5, 4.0);
        MSR2_EDGE_AWARE = b.comment("Enable edge-aware interpolation in spatial pass (preserves geometry edges).")
                .define("edgeAware", true);
        MSR2_SHARPNESS = b.comment("MSR spatial sharpening strength (CAS), 0 = off, 100 = strong.")
                .defineInRange("sharpness", 30, 0, 100);
        MSR2_DEPTH_THRESHOLD = b.comment("Depth difference threshold for temporal disocclusion detection.")
                .defineInRange("depthThreshold", 0.02, 0.005, 0.1);
        MSR2_HISTORY_WEIGHT = b.comment("Base history blend weight for temporal accumulation (0.0-1.0).")
                .defineInRange("historyWeight", 0.85, 0.5, 0.95);
        b.pop();

        b.comment("Dynamic Resolution Scaling (DRS) - auto-adjusts render scale based on FPS.").push("dynamicResolution");
        MSR2_DYNAMIC_RES = b.comment("Enable dynamic resolution scaling for MSR. Automatically lowers render scale when FPS drops.")
                .define("enabled", true);
        MSR2_TEMPORAL_MIN_FPS = b.comment("Minimum FPS threshold. Below this, temporal pass is disabled (falls back to spatial only).")
                .defineInRange("temporalMinFps", 40, 20, 60);
        MSR2_SCALE_STEP = b.comment("Step size for auto-scaling (percentage points per adjustment).")
                .defineInRange("scaleStep", 5, 1, 20);
        MSR2_MIN_SCALE = b.comment("Minimum render scale percentage (floor for dynamic scaling).")
                .defineInRange("minScale", 50, 25, 75);
        b.pop();

        SPEC = b.build();
    }

    private ModernOptConfig() {
    }
}

