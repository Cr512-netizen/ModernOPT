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
    private static volatile int renderScalePercent = 75;
    private static volatile int softness = 35;
    private static volatile int fsrEasuSharpness = 0;
    private static volatile int fsrRcasSharpness = 25;
    private static volatile UpscalerType upscaler = UpscalerType.FSR1;
    private static volatile MsaaLevel msaa = MsaaLevel.OFF;

    private ModernOptSettings() {
    }

    public static void refresh() {
        if (!ModernOptConfig.SPEC.isLoaded()) {
            return;
        }
        enabled = ModernOptConfig.ENABLED.get();
        overlay = ModernOptConfig.SHOW_OVERLAY.get();
        renderScalePercent = ModernOptConfig.RENDER_SCALE_PERCENT.get();
        upscaler = ModernOptConfig.UPSCALER.get();
        softness = ModernOptConfig.SOFTNESS.get();
        fsrEasuSharpness = ModernOptConfig.FSR_EASU_SHARPNESS.get();
        fsrRcasSharpness = ModernOptConfig.FSR_RCAS_SHARPNESS.get();
        msaa = ModernOptConfig.MSAA.get();
        ssaa = ModernOptConfig.SSAA.get();
        fxaa = ModernOptConfig.FXAA.get();
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
}
