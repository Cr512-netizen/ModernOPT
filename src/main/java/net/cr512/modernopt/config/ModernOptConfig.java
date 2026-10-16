package net.cr512.modernopt.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ModernOptConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue PROFILING_ENABLED;
    public static final ModConfigSpec.IntValue RENDER_SCALE_PERCENT;
    public static final ModConfigSpec.IntValue UPSCALER_MODE;
    public static final ModConfigSpec.BooleanValue FXAA_ENABLED;
    public static final ModConfigSpec.IntValue MSAA_SAMPLES;
    public static final ModConfigSpec.BooleanValue SSAA_ENABLED;
    public static final ModConfigSpec.BooleanValue DYNAMIC_RESOLUTION_ENABLED;
    public static final ModConfigSpec.BooleanValue IRIS_SHADOW_SCALE_ENABLED;
    public static final ModConfigSpec.IntValue IRIS_SHADOW_RESOLUTION;
    public static final ModConfigSpec.BooleanValue SHADOW_FRAME_SKIP_ENABLED;
    public static final ModConfigSpec.IntValue SHADOW_UPDATE_FPS;
    public static final ModConfigSpec.IntValue DYNAMIC_MIN_SCALE_PERCENT;
    public static final ModConfigSpec.IntValue DYNAMIC_MAX_SCALE_PERCENT;
    public static final ModConfigSpec.IntValue DYNAMIC_TARGET_FPS;
    public static final ModConfigSpec SPEC;

    static {
        BUILDER.push("general");
        // Keep render hooks disabled until the user explicitly enables the experimental pipeline.
        ENABLED = BUILDER.comment("Master switch for ModernOPT rendering features.").define("enabled", false);
        PROFILING_ENABLED = BUILDER.comment("Log low-overhead CPU timing and render-target dimensions every few seconds. Does not measure GPU execution time.").define("profilingEnabled", false);
        BUILDER.pop();
        BUILDER.push("rendering");
        RENDER_SCALE_PERCENT = BUILDER.comment("Internal render resolution as a percentage of the window size.").defineInRange("renderScalePercent", 100, 25, 100);
        UPSCALER_MODE = BUILDER.comment("Upscaler: 0 = none (direct blit), 1 = FSR1, 2 = Bilinear, 3 = Nearest Neighbor, 4 = SoftScale.").defineInRange("upscalerMode", 2, 0, 4);
        FXAA_ENABLED = BUILDER.define("fxaaEnabled", false);
        MSAA_SAMPLES = BUILDER.comment("0 = off, otherwise 2, 4, 8, or 16 samples.").defineInRange("msaaSamples", 0, 0, 16);
        SSAA_ENABLED = BUILDER.define("ssaaEnabled", false);
        DYNAMIC_RESOLUTION_ENABLED = BUILDER.define("dynamicResolutionEnabled", false);
        DYNAMIC_MIN_SCALE_PERCENT = BUILDER.defineInRange("dynamicMinScalePercent", 50, 25, 100);
        DYNAMIC_MAX_SCALE_PERCENT = BUILDER.defineInRange("dynamicMaxScalePercent", 100, 25, 100);
        DYNAMIC_TARGET_FPS = BUILDER.defineInRange("dynamicTargetFps", 120, 30, 540);
        BUILDER.pop();
        BUILDER.push("iris");
        IRIS_SHADOW_SCALE_ENABLED = BUILDER.comment("Scale Iris shader-pack shadow maps. Disabled by default for maximum shader-pack compatibility.").define("shadowScaleEnabled", false);
        IRIS_SHADOW_RESOLUTION = BUILDER.comment("Fixed Iris shadow-map resolution in pixels when shadow scaling is enabled.").defineInRange("shadowResolution", 1024, 512, 8192);
        SHADOW_FRAME_SKIP_ENABLED = BUILDER.comment("Update Iris shadow maps at a capped rate, reusing the previous shadow map on skipped frames.").define("shadowFrameSkipEnabled", false);
        SHADOW_UPDATE_FPS = BUILDER.comment("Target shadow-map refresh rate in FPS when dynamic shadow frame skipping is enabled.").defineInRange("shadowUpdateFps", 60, 40, 240);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private ModernOptConfig() {}
}
