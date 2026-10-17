package net.cr512.modernopt.render;

/**
 * Describes the temporary world-rendering resolution. Window dimensions are
 * scaled only while GameRenderer.renderLevel is running, so vanilla auxiliary
 * targets and viewport calculations use the same dimensions as the world target.
 * The phase is always ended before the native framebuffer is restored.
 */
public final class RenderScalePhase {
    private static boolean active;
    private static double scaleX = 1.0;
    private static double scaleY = 1.0;
    private static int activeRenderWidth;
    private static int activeRenderHeight;

    private RenderScalePhase() {}

    public static void begin(int renderWidth, int renderHeight, int nativeWidth, int nativeHeight) {
        if (renderWidth <= 0 || renderHeight <= 0 || nativeWidth <= 0 || nativeHeight <= 0) {
            end();
            return;
        }
        scaleX = renderWidth / (double) nativeWidth;
        scaleY = renderHeight / (double) nativeHeight;
        activeRenderWidth = renderWidth;
        activeRenderHeight = renderHeight;
        active = scaleX != 1.0 || scaleY != 1.0;
    }

    public static void end() {
        active = false;
        scaleX = 1.0;
        scaleY = 1.0;
        activeRenderWidth = 0;
        activeRenderHeight = 0;
    }

    public static int scaleWidth(int width) {
        return active ? Math.max(1, (int) Math.round(width * scaleX)) : width;
    }

    public static int scaleHeight(int height) {
        return active ? Math.max(1, (int) Math.round(height * scaleY)) : height;
    }

    public static double scaleGui(double guiScale) {
        return active ? guiScale * scaleX : guiScale;
    }

    /** Exact target dimensions for the current phase, even before Minecraft's main target is swapped. */
    public static int activeRenderWidth() {
        return active ? activeRenderWidth : 0;
    }

    public static int activeRenderHeight() {
        return active ? activeRenderHeight : 0;
    }

    public static boolean isActive() {
        return active;
    }
}
