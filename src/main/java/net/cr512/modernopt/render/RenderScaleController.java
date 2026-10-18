package net.cr512.modernopt.render;

import net.cr512.modernopt.config.ModernOptConfig;

/** Bounded FPS-targeted world render scale. HUD and GUI keep native resolution. */
public final class RenderScaleController {
    private static final int MIN_WIDTH = 320;
    private static final int MIN_HEIGHT = 180;
    private static final long ADJUST_INTERVAL_NANOS = 1_500_000_000L;
    private static final int SCALE_STEP = 1;
    private static final int REQUIRED_SAMPLES = 3;
    private static int belowTargetSamples;
    private static int aboveTargetSamples;
    private static final double DEAD_BAND = 0.10;
    private static int effectivePercent = -1;
    private static long lastAdjustmentNanos;
    private static int lastTargetFps = -1;
    private static boolean wasDynamic;

    private RenderScaleController() {}

    public static boolean isEnabled() {
        // The pipeline is opt-in through the master switch (disabled by default).
        // A hard-coded false here made render scale impossible to test at all.
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
        update(measuredFps, false);
    }

    /**
     * Dynamic scaling is paused while an Iris shader pack is active. RenderScale
     * documents stutter with dynamic scaling plus shaders; keep the last stable
     * scale instead of continuously resizing shader-owned render targets.
     */
    public static void update(int measuredFps, boolean shaderPackInUse) {
        boolean dynamic = ModernOptConfig.ENABLED.get() && ModernOptConfig.DYNAMIC_RESOLUTION_ENABLED.get();
        if (!dynamic) {
            wasDynamic = false;
            effectivePercent = -1;
            lastAdjustmentNanos = 0L;
            belowTargetSamples = 0;
            aboveTargetSamples = 0;
            return;
        }
        ensureInitialized();
        int target = ModernOptConfig.DYNAMIC_TARGET_FPS.get();
        int min = Math.min(ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get(), ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get());
        int max = Math.max(ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get(), ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get());
        effectivePercent = clamp(effectivePercent, min, max);
        if (target != lastTargetFps) { lastTargetFps = target; lastAdjustmentNanos = 0L; }

        // Reset hysteresis while shaders are active, so a backlog of old samples
        // cannot trigger an immediate resize when the shader pack is turned off.
        if (shaderPackInUse) {
            belowTargetSamples = 0;
            aboveTargetSamples = 0;
            return;
        }
        if (measuredFps <= 0) return;
        double ratio = measuredFps / (double) target;
        if (ratio < 1.0 - DEAD_BAND) { belowTargetSamples++; aboveTargetSamples = 0; }
        else if (ratio > 1.0 + DEAD_BAND) { aboveTargetSamples++; belowTargetSamples = 0; }
        else { belowTargetSamples = 0; aboveTargetSamples = 0; return; }
        if (belowTargetSamples < REQUIRED_SAMPLES && aboveTargetSamples < REQUIRED_SAMPLES) return;

        long now = System.nanoTime();
        if (lastAdjustmentNanos != 0L && now - lastAdjustmentNanos < ADJUST_INTERVAL_NANOS) return;
        if (belowTargetSamples >= REQUIRED_SAMPLES) effectivePercent = Math.max(min, effectivePercent - SCALE_STEP);
        else if (aboveTargetSamples >= REQUIRED_SAMPLES) effectivePercent = Math.min(max, effectivePercent + SCALE_STEP);
        lastAdjustmentNanos = now;
        belowTargetSamples = 0;
        aboveTargetSamples = 0;
    }

    public static int scaledWidth(int windowWidth) { return scaledDimension(windowWidth, effectivePercent(), MIN_WIDTH); }
    public static int scaledHeight(int windowHeight) { return scaledDimension(windowHeight, effectivePercent(), MIN_HEIGHT); }

    private static void ensureInitialized() {
        if (!wasDynamic || effectivePercent < 0) {
            int min = Math.min(ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get(), ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get());
            int max = Math.max(ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT.get(), ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT.get());
            effectivePercent = clamp(ModernOptConfig.RENDER_SCALE_PERCENT.get(), min, max);
            wasDynamic = true;
            lastAdjustmentNanos = 0L;
        }
    }

    private static int scaledDimension(int dimension, int percent, int minimum) {
        if (dimension <= 0) return dimension;
        int scaled = (int) Math.round(dimension * (clamp(percent, 25, 100) / 100.0));
        return Math.min(dimension, Math.max(Math.min(minimum, dimension), scaled));
    }

    private static int clamp(int value, int minimum, int maximum) { return Math.max(minimum, Math.min(maximum, value)); }
}
