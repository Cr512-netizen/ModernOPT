package net.cr512.modernopt.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ModernOptConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue RENDER_SCALE_PERCENT;
    public static final ModConfigSpec.IntValue UPSCALER_MODE;
    public static final ModConfigSpec.BooleanValue FXAA_ENABLED;
    public static final ModConfigSpec.IntValue MSAA_SAMPLES;
    public static final ModConfigSpec.BooleanValue SSAA_ENABLED;
    public static final ModConfigSpec.BooleanValue DYNAMIC_RESOLUTION_ENABLED;
    public static final ModConfigSpec.IntValue DYNAMIC_MIN_SCALE_PERCENT;
    public static final ModConfigSpec.IntValue DYNAMIC_MAX_SCALE_PERCENT;
    public static final ModConfigSpec.IntValue DYNAMIC_TARGET_FPS;
    public static final ModConfigSpec SPEC;

    static {
        BUILDER.push("general");
        // Off by default until the new render pipeline is complete and validated in-game.
        ENABLED = BUILDER.comment("Master switch. Experimental rendering is disabled by default.").define("enabled", false);
        BUILDER.pop();
        BUILDER.push("rendering");
        RENDER_SCALE_PERCENT = BUILDER.comment("Internal render resolution as a percentage of the window size.").defineInRange("renderScalePercent", 100, 25, 100);
        UPSCALER_MODE = BUILDER.comment("Upscaler: 0 = FSR1, 1 = Bilinear, 2 = Nearest Neighbor, 3 = SoftScale.").defineInRange("upscalerMode", 1, 0, 3);
        FXAA_ENABLED = BUILDER.define("fxaaEnabled", false);
        MSAA_SAMPLES = BUILDER.comment("0 = off, otherwise 2, 4, 8, or 16 samples.").defineInRange("msaaSamples", 0, 0, 16);
        SSAA_ENABLED = BUILDER.define("ssaaEnabled", false);
        DYNAMIC_RESOLUTION_ENABLED = BUILDER.define("dynamicResolutionEnabled", false);
        DYNAMIC_MIN_SCALE_PERCENT = BUILDER.defineInRange("dynamicMinScalePercent", 50, 25, 100);
        DYNAMIC_MAX_SCALE_PERCENT = BUILDER.defineInRange("dynamicMaxScalePercent", 100, 25, 100);
        DYNAMIC_TARGET_FPS = BUILDER.defineInRange("dynamicTargetFps", 120, 30, 540);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private ModernOptConfig() {}
}
