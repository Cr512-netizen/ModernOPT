package net.cr512.modernopt.config;

/** Multisample levels offered for the world pass. */
public enum MsaaLevel {
    OFF(1, "Off"),
    X2(2, "2x"),
    X4(4, "4x"),
    X8(8, "8x"),
    X16(16, "16x");

    private final int samples;
    private final String label;

    MsaaLevel(int samples, String label) {
        this.samples = samples;
        this.label = label;
    }

    public int samples() {
        return samples;
    }

    public String label() {
        return label;
    }

    public boolean enabled() {
        return samples > 1;
    }
}
