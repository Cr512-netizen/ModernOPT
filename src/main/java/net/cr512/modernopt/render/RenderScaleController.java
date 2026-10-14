package net.cr512.modernopt.render;

import net.cr512.modernopt.config.ModernOptConfig;

/** Bounded FPS-targeted world render scale. HUD and GUI keep native resolution. */
public final class RenderScaleController {
    private static final int MIN_WIDTH = 320;
    private static final int MIN_HEIGHT = 180;
    private static final int ADJUST_EVERY_FRAMES = 12;
    private static final int SCALE_STEP = 2;
    private static final double DEAD_BAND = 0.06;
    private static int effectivePercent = -1;
    private static int frameCounter;
    private static int lastTargetFps = -1;
    private static boolean wasDynamic;

    private RenderScaleController() {}

    public static boolean isEnabled() {
        return ModernOptConfig.ENABLED.get() && (ModernOptConfig.DYNAMIC_RESOLUTION_ENABLED.get() || ModernOptConfig.RENDER_SCALE_PERCENT.get() < 100);
    }

    public static int effectivePercent() {
        if (!ModernOptConfig.DYNAMIC_RESOLUTION_ENABLED.get()) return ModernOptConfig.RENDER_SCALE_PERCENT.get();
        ensureInitialized();
        int min = Math.min(ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get(), ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get());
        int max = Math.max(ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get(), ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get());
        return clamp(effectivePercent, min, max);
    }

    public static void update(int measuredFps) {
        boolean dynamic = ModernOptConfig.ENABLED.get() && ModernOptConfig.DYNAMIC_RESOLUTION_ENABLED.get();
        if (!dynamic) {
            wasDynamic = false;
            effectivePercent = -1;
            frameCounter = 0;
            return;
        }
        ensureInitialized();
        int target = ModernOptConfig.DYNAMIC_TARGET_FPS.get();
        int min = Math.min(ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get(), ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get());
        int max = Math.max(ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get(), ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get());
        effectivePercent = clamp(effectivePercent, min, max);
        if (target != lastTargetFps) { lastTargetFps = target; frameCounter = 0; }
        if (++frameCounter < ADJUST_EVERY_FRAMES) return;
        frameCounter = 0;
        if (measuredFps <= 0) return;
        double ratio = measuredFps / (double) target;
        if (ratio < 1.0 - DEAD_BAND) effectivePercent = Math.max(min, effectivePercent - SCALE_STEP);
        else if (ratio > 1.0 + DEAD_BAND) effectivePercent = Math.min(max, effectivePercent + SCALE_STEP);
    }

    public static int scaledWidth(int windowWidth) { return scaledDimension(windowWidth, effectivePercent(), MIN_WIDTH); }
    public static int scaledHeight(int windowHeight) { return scaledDimension(windowHeight, effectivePercent(), MIN_HEIGHT); }

    private static void ensureInitialized() {
        if (!wasDynamic || effectivePercent < 0) {
            int min = Math.min(ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get(), ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get());
            int max = Math.max(ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get(), ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get());
            effectivePercent = clamp(ModernOptConfig.RENDER_SCALE_PERCENT.get(), min, max);
            wasDynamic = true;
            frameCounter = 0;
        }
    }

    private static int scaledDimension(int dimension, int percent, int minimum) {
        if (dimension <= 0) return dimension;
        int scaled = (int) Math.round(dimension * (clamp(percent, 25, 100) / 100.0));
        return Math.min(dimension, Math.max(Math.min(minimum, dimension), scaled));
    }

    private static int clamp(int value, int minimum, int maximum) { return Math.max(minimum, Math.min(maximum, value)); }
}
