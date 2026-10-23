package net.cr512.modernopt.render;

/**
 * Pure resolution maths. No Minecraft types are touched here so the behaviour can be
 * covered by plain JUnit tests (see {@code src/test/java}).
 */
public final class RenderScaleMath {

    /** Lowest scale the mod accepts, in percent. */
    public static final int MIN_PERCENT = 25;

    private RenderScaleMath() {
    }

    /**
     * Scale factor applied to the window resolution for the internal world target.
     *
     * @param percent requested render scale in percent, clamped to {@code [25, 100]}
     * @param ssaa    when {@code true} the world is rendered at 200% and downsampled
     */
    public static double factor(int percent, boolean ssaa) {
        if (ssaa) {
            return 2.0D;
        }
        int clamped = Math.max(MIN_PERCENT, Math.min(100, percent));
        return clamped / 100.0D;
    }

    /**
     * Computes the internal render target size.
     *
     * @return a new {@code int[]{width, height}}, never smaller than 1x1 and never larger
     *         than {@code maxTextureSize}
     */
    public static int[] targetSize(int windowWidth, int windowHeight, int percent, boolean ssaa, int maxTextureSize) {
        double f = factor(percent, ssaa);
        int width = Math.max(1, (int) Math.round(Math.max(1, windowWidth) * f));
        int height = Math.max(1, (int) Math.round(Math.max(1, windowHeight) * f));

        int max = Math.max(1, maxTextureSize);
        if (width > max || height > max) {
            double shrink = Math.min((double) max / width, (double) max / height);
            width = Math.max(1, (int) Math.floor(width * shrink));
            height = Math.max(1, (int) Math.floor(height * shrink));
        }
        return new int[]{width, height};
    }

    /**
     * True when the frame actually needs the off-screen pipeline. At 100% without SSAA,
     * MSAA or FXAA there is nothing to gain, so vanilla rendering is left untouched.
     */
    public static boolean needsOffscreenPass(int percent, boolean ssaa, boolean msaa, boolean fxaa) {
        return ssaa || msaa || fxaa || Math.max(MIN_PERCENT, Math.min(100, percent)) != 100;
    }

    /** True when the reconstruction pass reduces the image instead of enlarging it. */
    public static boolean isDownsample(int windowWidth, int windowHeight, int targetWidth, int targetHeight) {
        return targetWidth > windowWidth || targetHeight > windowHeight;
    }
}
