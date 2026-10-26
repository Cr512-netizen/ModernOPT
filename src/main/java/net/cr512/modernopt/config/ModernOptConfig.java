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
    public static final ModConfigSpec.IntValue FSR1_SHARPNESS;
    public static final ModConfigSpec.EnumValue<MsaaLevel> MSAA;
    public static final ModConfigSpec.BooleanValue SSAA;
    public static final ModConfigSpec.BooleanValue FXAA;
    public static final ModConfigSpec.BooleanValue SHOW_OVERLAY;

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
        FSR1_SHARPNESS = b.comment("FSR 1 / FSR 2 RCAS sharpening, 0 = off, 100 = strong.")
                .defineInRange("fsr1Sharpness", 25, 0, 100);
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

        SPEC = b.build();
    }

    private ModernOptConfig() {
    }
}
