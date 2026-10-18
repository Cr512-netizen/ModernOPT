package net.cr512.modernopt.render;

/** Executed by Gradle verifyAdaptiveScale; no test framework or game client required. */
public final class AdaptiveScaleControllerTest {
    private AdaptiveScaleControllerTest() {}

    public static void main(String[] args) {
        sustainedSlowFramesLowerScaleWithinBounds();
        fastFramesRecoverScale();
        stationaryCameraDoesNotPumpScale();
        isolatedStallsAreIgnored();
        pauseResetsTiming();
        reversedBoundsAreNormalized();
        System.out.println("Adaptive render-scale regression checks passed");
    }

    private static void sustainedSlowFramesLowerScaleWithinBounds() {
        AdaptiveScaleController c = new AdaptiveScaleController();
        int scale = 100; long now = 0L;
        for (int i = 0; i < 30 * 30; i++) {
            scale = c.update(now, scale, 60, 50, 100, false, true);
            now += 1_000_000_000L / 30;
            check(scale >= 50 && scale <= 100, "scale escaped bounds under load");
        }
        check(scale == 50, "sustained low FPS should reach the configured minimum");
    }

    private static void fastFramesRecoverScale() {
        AdaptiveScaleController c = new AdaptiveScaleController();
        int scale = 50; long now = 0L;
        for (int i = 0; i < 12 * 120; i++) {
            scale = c.update(now, scale, 60, 50, 100, false, true);
            now += 1_000_000_000L / 120;
        }
        check(scale > 50 && scale <= 100, "spare frame-time headroom should recover resolution safely");
    }

    private static void stationaryCameraDoesNotPumpScale() {
        AdaptiveScaleController c = new AdaptiveScaleController();
        int scale = 100; long now = 0L;
        for (int i = 0; i < 3 * 30; i++) {
            scale = c.update(now, scale, 60, 50, 100, false, false);
            now += 1_000_000_000L / 30;
        }
        check(scale == 100, "stationary camera must hold scale");
        int beforeMovement = scale;
        scale = c.update(now, scale, 60, 50, 100, false, true);
        check(scale == beforeMovement, "movement resume must not trigger an immediate scale jump");
        now += 1_000_000_000L / 30;
        for (int i = 0; i < 20; i++) {
            scale = c.update(now, scale, 60, 50, 100, false, true);
            now += 1_000_000_000L / 30;
        }
        check(scale < 100, "scale should adapt after a fresh moving-camera interval");
    }

    private static void isolatedStallsAreIgnored() {
        AdaptiveScaleController c = new AdaptiveScaleController();
        int scale = 100; long now = 0L;
        for (int i = 0; i < 10; i++) {
            scale = c.update(now, scale, 60, 50, 100, false, true);
            now += 1_000_000_000L / 30;
        }
        int beforeStall = scale;
        now += 2_000_000_000L;
        scale = c.update(now, scale, 60, 50, 100, false, true);
        check(scale == beforeStall, "isolated long stall must not change scale");
        for (int i = 0; i < 5; i++) {
            now += 1_000_000_000L / 30;
            scale = c.update(now, scale, 60, 50, 100, false, true);
        }
        check(scale == beforeStall, "stall recovery must restart sampling without sudden downscale");
    }

    private static void pauseResetsTiming() {
        AdaptiveScaleController c = new AdaptiveScaleController();
        int scale = 82;
        c.update(0L, scale, 60, 50, 100, false, true);
        scale = c.update(16_000_000L, scale, 60, 50, 100, true, true);
        check(scale == 82, "pause must preserve current scale");
        scale = c.update(4_000_000_000L, scale, 60, 50, 100, false, true);
        check(scale == 82, "resume must seed timing without immediate resize");
    }

    private static void reversedBoundsAreNormalized() {
        AdaptiveScaleController c = new AdaptiveScaleController();
        int scale = c.update(0L, 70, 60, 90, 50, false, true);
        check(scale == 70, "first sample should preserve an in-range scale");
        scale = c.update(600_000_000L, scale, 60, 90, 50, false, true);
        check(scale >= 50 && scale <= 90, "reversed bounds must be normalized");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
