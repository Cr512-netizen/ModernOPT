package net.cr512.modernopt.render;

/** Shared frame-local state between optional Iris mixins. */
public final class IrisShadowFrameSkipState {
    public static volatile boolean skipShadowPass;
    public static volatile boolean insideShadowSetup;

    private IrisShadowFrameSkipState() {}
}
