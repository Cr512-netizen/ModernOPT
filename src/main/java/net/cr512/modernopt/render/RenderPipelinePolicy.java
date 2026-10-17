package net.cr512.modernopt.render;

/**
 * Pure render-feature selection, extracted from the GameRenderer mixin as the
 * first step of the renderer rewrite. It decides which ModernOPT pass may run;
 * it does not allocate targets or touch OpenGL state.
 */
public final class RenderPipelinePolicy {
    public record Selection(boolean ssaa, boolean fxaa, boolean scaling, int msaaSamples) {}

    private RenderPipelinePolicy() {}

    public static Selection select(
        boolean shaderPackInUse,
        boolean scalingEnabled,
        boolean ssaaEnabled,
        boolean fxaaEnabled,
        int requestedSamples,
        int failedSamples
    ) {
        boolean ssaa = !shaderPackInUse && ssaaEnabled;
        boolean fxaa = !shaderPackInUse && !ssaa && fxaaEnabled;
        // AA modes are mutually exclusive. Shader packs own their AA pipeline.
        int samples = (shaderPackInUse || ssaa || fxaa) ? 0 : normalizeSamples(requestedSamples);
        if (samples == failedSamples) samples = 0;
        boolean scaling = !ssaa && scalingEnabled;
        return new Selection(ssaa, fxaa, scaling, samples);
    }

    private static int normalizeSamples(int samples) {
        return switch (samples) {
            case 2, 4, 8, 16 -> samples;
            default -> 0;
        };
    }
}
