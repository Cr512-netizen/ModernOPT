// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.config;

/** Reconstruction filters available for scaling the world image back to window size. */
public enum UpscalerType {
    /** Plain hardware bilinear stretch. Cheapest, slightly blurry. */
    BILINEAR("Bilinear", false),
    /** Nearest neighbour. Blocky and cheap, useful for pixel-art packs. */
    NEAREST("Nearest", false),
    /** 9-tap tent filter with a tunable softening radius. */
    SOFT("Soft scale", true),
    /** AMD FidelityFX Super Resolution 1: EASU reconstruction + RCAS sharpening. */
    FSR1("FSR 1", true),
    /**
     * Temporal reconstruction (formerly MSR): jittered world pass, camera reprojection, history
     * accumulation, then RCAS. Uses the same sharpness setting as FSR 1.
     */
    MSR("MSR", true),
    /**
     * MSR2: variance-clipping temporal pass + fused EASU+RCAS+edge-aware spatial pass.
     * Supports both compute shader (GL 4.3+) and fragment shader fallback paths.
     */
    MSR2("MSR2", true);

    private final String label;
    private final boolean shader;

    UpscalerType(String label, boolean shader) {
        this.label = label;
        this.shader = shader;
    }

    public String label() {
        return label;
    }

    /** True when the filter needs our own GLSL pass instead of a hardware blit. */
    public boolean needsShaderPass() {
        return shader;
    }

    public UpscalerType next() {
        UpscalerType[] v = values();
        return v[(ordinal() + 1) % v.length];
    }
}
