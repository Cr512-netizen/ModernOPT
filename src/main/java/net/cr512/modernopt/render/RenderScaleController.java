package net.cr512.modernopt.render;

import net.cr512.modernopt.config.ModernOptConfig;

/**
 * Pure sizing policy for the renderer. It deliberately does not mutate Minecraft's
 * main target: that would also downscale GUI/HUD and can break other renderers.
 * World-only target integration is a separate step.
 */
public final class RenderScaleController {
    private static final int MIN_WIDTH = 320;
    private static final int MIN_HEIGHT = 180;

    private RenderScaleController() {}

    public static boolean isEnabled() {
        return ModernOptConfig.ENABLED.get() && ModernOptConfig.RENDER_SCALE_PERCENT.get() < 100;
    }

    public static int scaledWidth(int windowWidth) {
        return scaledDimension(windowWidth, ModernOptConfig.RENDER_SCALE_PERCENT.get(), MIN_WIDTH);
    }

    public static int scaledHeight(int windowHeight) {
        return scaledDimension(windowHeight, ModernOptConfig.RENDER_SCALE_PERCENT.get(), MIN_HEIGHT);
    }

    private static int scaledDimension(int dimension, int percent, int minimum) {
        if (dimension <= 0) return dimension;
        int safePercent = Math.max(50, Math.min(100, percent));
        int scaled = (int) Math.round(dimension * (safePercent / 100.0));
        return Math.min(dimension, Math.max(Math.min(minimum, dimension), scaled));
    }
}
