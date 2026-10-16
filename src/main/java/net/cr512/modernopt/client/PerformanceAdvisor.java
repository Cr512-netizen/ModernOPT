package net.cr512.modernopt.client;

import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.render.RenderDiagnostics;

/**
 * Conservative, non-invasive hints based on client-side measurements.
 * Recommendations never modify settings automatically and never claim to measure GPU time.
 */
public final class PerformanceAdvisor {
    private PerformanceAdvisor() {}

    public static String recommendation(double p95FrameTimeMs, double processCpuPercent,
                                        long heapUsedMb, long heapMaxMb,
                                        boolean shaderPackActive) {
        if (!ModernOptConfig.ENABLED.get() || p95FrameTimeMs <= 0.0) {
            return "modernopt.advisor.collecting";
        }

        double worldCpuMs = RenderDiagnostics.lastFrameCpuMs();
        double postCpuMs = RenderDiagnostics.lastPostCpuMs();

        if (heapMaxMb > 0 && heapUsedMb >= heapMaxMb * 0.88) {
            return "modernopt.advisor.memory";
        }
        if (worldCpuMs > 0.0 && worldCpuMs >= Math.max(5.0, p95FrameTimeMs * 0.55)) {
            return "modernopt.advisor.world_cpu";
        }
        if (postCpuMs > 0.0 && postCpuMs >= Math.max(2.0, p95FrameTimeMs * 0.15)) {
            return "modernopt.advisor.post_cpu";
        }
        if (p95FrameTimeMs >= 25.0 && shaderPackActive) {
            return "modernopt.advisor.shaderpack";
        }
        if (p95FrameTimeMs >= 25.0 && processCpuPercent >= 80.0) {
            return "modernopt.advisor.cpu_load";
        }
        if (p95FrameTimeMs >= 25.0) {
            return "modernopt.advisor.compare_scale";
        }
        return "modernopt.advisor.healthy";
    }
}
