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
    public static final ModConfigSpec.BooleanValue SHOW_OVERLAY;

    // MSR 2.0 config
    public static final ModConfigSpec.BooleanValue MSR_ENABLED;
    public static final ModConfigSpec.DoubleValue MSR_VARIANCE_SCALE;
    public static final ModConfigSpec.BooleanValue MSR_EDGE_AWARE;
    public static final ModConfigSpec.IntValue MSR_SHARPNESS;
    public static final ModConfigSpec.DoubleValue MSR_DEPTH_THRESHOLD;
    public static final ModConfigSpec.DoubleValue MSR_HISTORY_WEIGHT;
    public static final ModConfigSpec.BooleanValue MSR_DYNAMIC_RES;
    public static final ModConfigSpec.IntValue MSR_TEMPORAL_MIN_FPS;
    public static final ModConfigSpec.IntValue MSR_SCALE_STEP;
    public static final ModConfigSpec.IntValue MSR_MIN_SCALE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Master switch. When disabled the mod touches nothing.").push("general");
        ENABLED = b.comment("Enable ModernOPT render modifications.").define("enabled", true);
        SHOW_OVERLAY = b.comment("Show the FPS / resolution overlay.").define("showOverlay", false);
        b.pop();

        b.comment("Internal world render resolution.").push("renderScale");
        RENDER_SCALE_PERCENT = b.comment("Internal world resolution as a percentage of the window (25-100).")
                .defineInRange("percent", 75, 25, 100);
        UPSCALER = b.comment("Reconstruction filter used to scale back up.")
                .defineEnum("upscaler", UpscalerType.FSR1);
        SOFTNESS = b.comment("SoftScale blur strength, 0 = neutral, 100 = very soft.")
                .defineInRange("softness", 35, 0, 100);
        FSR_EASU_SHARPNESS = b.comment("FSR 1 / FSR 2 EASU sharpening, 0 = off, 100 = strong.")
                .defineInRange("fsrEasuSharpness", 0, 0, 100);
        FSR_RCAS_SHARPNESS = b.comment("FSR 1 / FSR 2 RCAS sharpening, 0 = off, 100 = strong.")
                .defineInRange("fsrRcasSharpness", 25, 0, 100);
        b.pop();

        b.comment("Anti-aliasing.").push("antialiasing");
        MSAA = b.comment("Multisample anti-aliasing for the world pass.",
                        "Requires the world to be rendered off-screen; ignored in Fabulous mode.")
                .defineEnum("msaa", MsaaLevel.OFF);
        SSAA = b.comment("Render the world at 2x width and height, then downsample.",
                        "Takes priority over render scale and MSAA.")
                .define("ssaa", false);
        FXAA = b.comment("Post-process edge smoothing on the final image.").define("fxaa", false);
        b.pop();

        b.comment("MSR 2.0 (Minecraft Super Resolution) - experimental upscaler.").push("msr");
        MSR_ENABLED = b.comment("Enable MSR 2.0 upscaler (experimental). Overrides other upscalers when enabled.")
                .define("enabled", false);
        MSR_VARIANCE_SCALE = b.comment("Variance clipping threshold multiplier for temporal pass. Higher = more aggressive history rejection.")
                .defineInRange("varianceScale", 2.0, 0.5, 4.0);
        MSR_EDGE_AWARE = b.comment("Enable edge-aware interpolation in spatial pass (preserves geometry edges).")
                .define("edgeAware", true);
        MSR_SHARPNESS = b.comment("MSR spatial sharpening strength (CAS), 0 = off, 100 = strong.")
                .defineInRange("sharpness", 30, 0, 100);
        MSR_DEPTH_THRESHOLD = b.comment("Depth difference threshold for temporal disocclusion detection.")
                .defineInRange("depthThreshold", 0.02, 0.005, 0.1);
        MSR_HISTORY_WEIGHT = b.comment("Base history blend weight for temporal accumulation (0.0-1.0).")
                .defineInRange("historyWeight", 0.85, 0.5, 0.95);
        b.pop();

        b.comment("Dynamic Resolution Scaling (DRS) - auto-adjusts render scale based on FPS.").push("dynamicResolution");
        MSR_DYNAMIC_RES = b.comment("Enable dynamic resolution scaling for MSR. Automatically lowers render scale when FPS drops.")
                .define("enabled", true);
        MSR_TEMPORAL_MIN_FPS = b.comment("Minimum FPS threshold. Below this, temporal pass is disabled (falls back to spatial only).")
                .defineInRange("temporalMinFps", 40, 20, 60);
        MSR_SCALE_STEP = b.comment("Step size for auto-scaling (percentage points per adjustment).")
                .defineInRange("scaleStep", 5, 1, 20);
        MSR_MIN_SCALE = b.comment("Minimum render scale percentage (floor for dynamic scaling).")
                .defineInRange("minScale", 50, 25, 75);
        b.pop();

        SPEC = b.build();
    }

    private ModernOptConfig() {
    }
}

