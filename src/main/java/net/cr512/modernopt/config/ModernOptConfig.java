package net.cr512.modernopt.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ModernOptConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue PROFILING_ENABLED;
    public static final ModConfigSpec.IntValue DIAGNOSTICS_SAMPLE_INTERVAL_FRAMES;
    public static final ModConfigSpec.IntValue BENCHMARK_DURATION_SECONDS;
    public static final ModConfigSpec.BooleanValue BENCHMARK_CAPTURE_CPU;
    public static final ModConfigSpec.BooleanValue BENCHMARK_CAPTURE_MEMORY;
    public static final ModConfigSpec.BooleanValue OVERLAY_ENABLED;
    public static final ModConfigSpec.BooleanValue LOGGING_ENABLED;
    public static final ModConfigSpec.BooleanValue OVERLAY_SHOW_FPS;
    public static final ModConfigSpec.BooleanValue OVERLAY_SHOW_CPU;
    public static final ModConfigSpec.BooleanValue OVERLAY_SHOW_FRAME_TIME;
    public static final ModConfigSpec.BooleanValue OVERLAY_SHOW_MEMORY;
    public static final ModConfigSpec.BooleanValue OVERLAY_SHOW_RENDER_INFO;
    public static final ModConfigSpec.BooleanValue OVERLAY_SHOW_ONE_PERCENT_LOW;
    public static final ModConfigSpec.BooleanValue OVERLAY_SHOW_P95_FRAME_TIME;
    public static final ModConfigSpec.IntValue OVERLAY_BACKGROUND_OPACITY;
    public static final ModConfigSpec.IntValue OVERLAY_POSITION;
    public static final ModConfigSpec.IntValue LOG_INTERVAL_SECONDS;
    public static final ModConfigSpec.BooleanValue PARTICLE_CULLING_ENABLED;
    public static final ModConfigSpec.IntValue PARTICLE_RENDER_DISTANCE;
    public static final ModConfigSpec.IntValue RENDER_SCALE_PERCENT;
    public static final ModConfigSpec.IntValue UPSCALER_MODE;
    public static final ModConfigSpec.BooleanValue FSR1_FP16_ENABLED;
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
        DIAGNOSTICS_SAMPLE_INTERVAL_FRAMES = BUILDER.comment("Record one benchmark row every N rendered HUD frames to reduce measurement overhead.").defineInRange("diagnosticsSampleIntervalFrames", 1, 1, 10);
        BENCHMARK_DURATION_SECONDS = BUILDER.comment("Automatic benchmark stop duration in seconds; 0 means manual stop.").defineInRange("benchmarkDurationSeconds", 60, 0, 300);
        BENCHMARK_CAPTURE_CPU = BUILDER.comment("Include process and system CPU load in diagnostic CSV samples.").define("benchmarkCaptureCpu", true);
        BENCHMARK_CAPTURE_MEMORY = BUILDER.comment("Include Java heap usage in diagnostic CSV samples.").define("benchmarkCaptureMemory", true);
        OVERLAY_ENABLED = BUILDER.comment("Show the configurable performance overlay in-game.").define("overlayEnabled", true);
        LOGGING_ENABLED = BUILDER.comment("Append performance samples to config/modernopt-performance.txt.").define("loggingEnabled", false);
        OVERLAY_SHOW_FPS = BUILDER.define("overlayShowFps", true);
        OVERLAY_SHOW_CPU = BUILDER.define("overlayShowCpu", true);
        OVERLAY_SHOW_FRAME_TIME = BUILDER.define("overlayShowFrameTime", true);
        OVERLAY_SHOW_MEMORY = BUILDER.define("overlayShowMemory", true);
        OVERLAY_SHOW_RENDER_INFO = BUILDER.define("overlayShowRenderInfo", true);
        OVERLAY_SHOW_ONE_PERCENT_LOW = BUILDER.define("overlayShowOnePercentLow", true);
        OVERLAY_SHOW_P95_FRAME_TIME = BUILDER.define("overlayShowP95FrameTime", true);
        OVERLAY_BACKGROUND_OPACITY = BUILDER.defineInRange("overlayBackgroundOpacity", 56, 0, 100);
        OVERLAY_POSITION = BUILDER.comment("Overlay corner: 0=top-left, 1=top-right, 2=bottom-left, 3=bottom-right.").defineInRange("overlayPosition", 0, 0, 3);
        LOG_INTERVAL_SECONDS = BUILDER.comment("Performance log sampling interval in seconds.").defineInRange("logIntervalSeconds", 5, 1, 60);
        PARTICLE_CULLING_ENABLED = BUILDER.comment("Skip drawing particles farther than the configured distance; particles still tick normally.").define("particleCullingEnabled", false);
        PARTICLE_RENDER_DISTANCE = BUILDER.comment("Maximum particle draw distance in blocks when particle culling is enabled.").defineInRange("particleRenderDistance", 96, 16, 256);
        BUILDER.pop();
        BUILDER.push("rendering");
        RENDER_SCALE_PERCENT = BUILDER.comment("Internal render resolution as a percentage of the window size.").defineInRange("renderScalePercent", 100, 25, 100);
        UPSCALER_MODE = BUILDER.comment("Upscaler: 0 = none (direct blit), 1 = FSR1, 2 = Bilinear, 3 = Nearest Neighbor, 4 = SoftScale.").defineInRange("upscalerMode", 2, 0, 4);
        FSR1_FP16_ENABLED = BUILDER.comment("Use native FP16 EASU/RCAS when supported; disable to compare against FP32.").define("fsr1Fp16Enabled", true);
        FXAA_ENABLED = BUILDER.define("fxaaEnabled", false);
        MSAA_SAMPLES = BUILDER.comment("0 = off, otherwise 2, 4, or 8 samples. 8x is the maximum to avoid the extreme cost of 16x.").defineInRange("msaaSamples", 0, 0, 8);
        SSAA_ENABLED = BUILDER.define("ssaaEnabled", false);
        DYNAMIC_RESOLUTION_ENABLED = BUILDER.comment("Adjust world scale from frame cadence; dynamic adjustments pause while an Iris shader pack is active.").define("dynamicResolutionEnabled", false);
        DYNAMIC_MIN_SCALE_PERCENT = BUILDER.defineInRange("dynamicMinScalePercent", 50, 25, 100);
        DYNAMIC_MAX_SCALE_PERCENT = BUILDER.defineInRange("dynamicMaxScalePercent", 100, 25, 100);
        DYNAMIC_TARGET_FPS = BUILDER.defineInRange("dynamicTargetFps", 120, 30, 540);
        BUILDER.pop();
        BUILDER.push("iris");
        IRIS_SHADOW_SCALE_ENABLED = BUILDER.comment("Scale Iris shader-pack shadow maps. Disabled by default for maximum shader-pack compatibility.").define("shadowScaleEnabled", false);
        IRIS_SHADOW_RESOLUTION = BUILDER.comment("Fixed Iris shadow-map resolution in pixels when shadow scaling is enabled.").defineInRange("shadowResolution", 1024, 512, 8192);
        SHADOW_FRAME_SKIP_ENABLED = BUILDER.comment("Cache Iris shadow maps and their matching matrices between refreshes; experimental and disabled by default.").define("shadowFrameSkipEnabled", false);
        SHADOW_UPDATE_FPS = BUILDER.comment("Maximum Iris shadow refresh rate when shadow-frame skipping is enabled.").defineInRange("shadowUpdateFps", 60, 40, 240);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private ModernOptConfig() {}
}
