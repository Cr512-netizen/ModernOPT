package net.cr512.modernopt.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ModernOptConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue RENDER_SCALE_PERCENT;
    public static final ModConfigSpec.IntValue UPSCALER_MODE;
    public static final ModConfigSpec.BooleanValue FXAA_ENABLED;
    public static final ModConfigSpec SPEC;

    static {
        BUILDER.push("general");
        ENABLED = BUILDER.comment("Master switch. When false, ModernOPT rendering features must not intervene.").define("enabled", true);
        BUILDER.pop();
        BUILDER.push("rendering");
        RENDER_SCALE_PERCENT = BUILDER.comment("Internal render resolution as a percentage of the window size.").defineInRange("renderScalePercent", 100, 50, 100);
        UPSCALER_MODE = BUILDER.comment("Upscaler: 0 = FSR1 (EASU + RCAS), 1 = Bilinear, 2 = Nearest Neighbor, 3 = SoftScale (soft Gaussian reconstruction).").defineInRange("upscalerMode", 0, 0, 3);
        FXAA_ENABLED = BUILDER.comment("Enable FXAA on the world image. Automatically skipped while an Iris shader pack is active.").define("fxaaEnabled", false);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private ModernOptConfig() {}
}
