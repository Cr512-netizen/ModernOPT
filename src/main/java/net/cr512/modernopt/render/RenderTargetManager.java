package net.cr512.modernopt.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import net.cr512.modernopt.config.ModernOptConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns ModernOPT's private world render targets. Iris/Minecraft-owned targets
 * are deliberately never allocated, resized, or destroyed here.
 */
public final class RenderTargetManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ModernOPT");

    private TextureTarget scaledTarget;
    private MultisampleRenderTarget msaaTarget;
    private int allocatedSamples;
    private int failedMsaaRequest;

    public boolean isMsaaRequestFailed(int samples) {
        return samples > 0 && failedMsaaRequest == samples;
    }

    public MultisampleRenderTarget acquireMsaaTarget(int width, int height, int requestedSamples, boolean onOSX) {
        if (requestedSamples != 2 && requestedSamples != 4 && requestedSamples != 8) {
            throw new IllegalArgumentException("MSAA request must be 2x, 4x, or 8x");
        }

        if (msaaTarget == null || allocatedSamples != requestedSamples) {
            destroyMsaaTarget();
            int[] candidates = requestedSamples == 8 ? new int[]{8, 4, 2}
                : requestedSamples == 4 ? new int[]{4, 2} : new int[]{2};
            Throwable lastFailure = null;
            for (int candidate : candidates) {
                try {
                    msaaTarget = new MultisampleRenderTarget(width, height, candidate, onOSX);
                    allocatedSamples = candidate;
                    break;
                } catch (RuntimeException | LinkageError failure) {
                    lastFailure = failure;
                    msaaTarget = null;
                }
            }

            if (msaaTarget == null) {
                allocatedSamples = 0;
                if (lastFailure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
                if (lastFailure instanceof LinkageError linkageFailure) throw linkageFailure;
                throw new IllegalStateException("No supported MSAA sample count could be allocated.");
            }

            if (allocatedSamples != requestedSamples) {
                LOGGER.warn("MSAA {}x is unavailable; falling back to {}x.", requestedSamples, allocatedSamples);
                ModernOptConfig.MSAA_SAMPLES.set(allocatedSamples);
                ModernOptConfig.SPEC.save();
            }
        } else if (msaaTarget.width != width || msaaTarget.height != height) {
            msaaTarget.resize(width, height, onOSX);
        }

        failedMsaaRequest = 0;
        return msaaTarget;
    }

    public TextureTarget acquireScaledTarget(int width, int height, boolean onOSX) {
        if (scaledTarget == null) {
            scaledTarget = new TextureTarget(width, height, true, onOSX);
        } else if (scaledTarget.width != width || scaledTarget.height != height) {
            try {
                scaledTarget.resize(width, height, onOSX);
            } catch (RuntimeException | LinkageError resizeFailure) {
                // A failed resize may leave the target partially recreated. Do not
                // reuse that object on a later frame; release it and retry cleanly.
                try {
                    scaledTarget.destroyBuffers();
                } catch (RuntimeException | LinkageError cleanupFailure) {
                    resizeFailure.addSuppressed(cleanupFailure);
                } finally {
                    scaledTarget = null;
                }
                throw resizeFailure;
            }
        }
        return scaledTarget;
    }

    public void markMsaaFailure(int requestedSamples) {
        destroyMsaaTarget();
        failedMsaaRequest = requestedSamples;
    }

    public void destroy() {
        // Detach references before invoking GL cleanup. A driver/context error must
        // not leave a partially destroyed target eligible for reuse on a later frame.
        destroyMsaaTarget();
        TextureTarget target = scaledTarget;
        scaledTarget = null;
        if (target != null) {
            try {
                target.destroyBuffers();
            } catch (RuntimeException | LinkageError cleanupFailure) {
                LOGGER.warn("Could not fully release ModernOPT's scaled world target.", cleanupFailure);
            }
        }
        failedMsaaRequest = 0;
    }

    private void destroyMsaaTarget() {
        MultisampleRenderTarget target = msaaTarget;
        msaaTarget = null;
        allocatedSamples = 0;
        if (target != null) {
            try {
                target.destroyBuffers();
            } catch (RuntimeException | LinkageError cleanupFailure) {
                LOGGER.warn("Could not fully release ModernOPT's multisample world target.", cleanupFailure);
            }
        }
    }
}
