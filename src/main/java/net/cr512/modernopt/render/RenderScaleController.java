package net.cr512.modernopt.render;

import net.cr512.modernopt.config.ModernOptConfig;

/** Bounded, smoothed FPS-targeted world render scale. HUD and GUI keep native resolution. */
public final class RenderScaleController {
    private static final int MIN_WIDTH = 320;
    private static final int MIN_HEIGHT = 180;
    private static final AdaptiveScaleController ADAPTIVE = new AdaptiveScaleController();
    private static int effectivePercent = -1;
    private static int lastTargetFps = -1;
    private static boolean wasDynamic;

    private RenderScaleController() {}

    public static boolean isEnabled() {
        return ModernOptConfig.ENABLED.get()
            && (ModernOptConfig.DYNAMIC_RESOLUTION_ENABLED.get() || ModernOptConfig.RENDER_SCALE_PERCENT.get() < 100);
    }

    public static int effectivePercent() {
        if (!ModernOptConfig.DYNAMIC_RESOLUTION_ENABLED.get()) return ModernOptConfig.RENDER_SCALE_PERCENT.get();
        ensureInitialized();
        int min = Math.min(ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get(), ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get());
        int max = Math.max(ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get(), ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get());
        return clamp(effectivePercent, min, max);
    }

    public static void update(boolean shaderPackInUse) {
        update(shaderPackInUse, true, false);
    }

    public static void update(boolean shaderPackInUse, boolean worldActive) {
        update(shaderPackInUse, worldActive, false);
    }

    /** Smooths frame cadence, ignores isolated stalls, and pauses resizing for shaders, menus and a stationary camera. */
    public static void update(boolean shaderPackInUse, boolean worldActive, boolean cameraMoving) {
        boolean dynamic = ModernOptConfig.ENABLED.get() && ModernOptConfig.DYNAMIC_RESOLUTION_ENABLED.get();
        if (!dynamic) {
            wasDynamic = false;
            effectivePercent = -1;
            lastTargetFps = -1;
            ADAPTIVE.resetTiming();
            return;
        }

        ensureInitialized();
        int target = ModernOptConfig.DYNAMIC_TARGET_FPS.get();
        int min = Math.min(ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get(), ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get());
        int max = Math.max(ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get(), ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get());
        effectivePercent = clamp(effectivePercent, min, max);
        if (target != lastTargetFps) {
            lastTargetFps = target;
            ADAPTIVE.resetTiming();
        }
        effectivePercent = ADAPTIVE.update(
            System.nanoTime(), effectivePercent, target, min, max,
            shaderPackInUse || !worldActive, cameraMoving
        );
    }

    public static int scaledWidth(int windowWidth) { return scaledDimension(windowWidth, effectivePercent(), MIN_WIDTH); }
    public static int scaledHeight(int windowHeight) { return scaledDimension(windowHeight, effectivePercent(), MIN_HEIGHT); }

    private static void ensureInitialized() {
        if (!wasDynamic || effectivePercent < 0) {
            int min = Math.min(ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get(), ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get());
            int max = Math.max(ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get(), ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get());
            effectivePercent = clamp(ModernOptConfig.RENDER_SCALE_PERCENT.get(), min, max);
            wasDynamic = true;
            ADAPTIVE.resetTiming();
        }
    }

    private static int scaledDimension(int dimension, int percent, int minimum) {
        if (dimension <= 0) return dimension;
        int scaled = (int) Math.round(dimension * (clamp(percent, 25, 100) / 100.0));
        return Math.min(dimension, Math.max(Math.min(minimum, dimension), scaled));
    }

    private static int clamp(int value, int minimum, int maximum) { return Math.max(minimum, Math.min(maximum, value)); }
}
