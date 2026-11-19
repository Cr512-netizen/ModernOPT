// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.render;

import com.mojang.blaze3d.pipeline.TextureTarget;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal colour+depth target for the single-main-target fast path.
 *
 * <p>Unlike {@link ScaledRenderTarget} it has no multisampling, HDR or history plumbing: plain
 * world/FSR 1 frames only need a small colour texture and a depth attachment. Keeping it simple
 * is the point - every extra attachment and version counter is work Iris and vanilla have to
 * track.</p>
 *
 * <p>The buffer versions are still distinct from the native target's. Iris compares them when
 * the main target changes and re-attaches its gbuffer depth only when the version moves; two
 * fresh targets that both report {@code 0} look identical to Iris and leave it sampling a stale
 * depth texture. Starting well above the range vanilla uses keeps the two instances apart.</p>
 */
public final class FastWorldTarget extends TextureTarget {

    private static final AtomicInteger IRIS_VERSION = new AtomicInteger(0x50000000);

    private static int nextIrisVersion() {
        return IRIS_VERSION.getAndIncrement();
    }

    private int irisDepthBufferVersion = nextIrisVersion();
    private int irisColorBufferVersion = nextIrisVersion();

    public FastWorldTarget(int width, int height) {
        super(width, height, true, false);
    }

    @Override
    public void createBuffers(int width, int height, boolean clear) {
        super.createBuffers(width, height, clear);
        // Fresh attachments: Iris has to re-attach its depth source.
        this.irisDepthBufferVersion = nextIrisVersion();
        this.irisColorBufferVersion = nextIrisVersion();
    }

    @Override
    public void destroyBuffers() {
        super.destroyBuffers();
        this.irisDepthBufferVersion = nextIrisVersion();
        this.irisColorBufferVersion = nextIrisVersion();
    }

    @Override
    public void bindWrite(boolean setViewport) {
        super.bindWrite(setViewport);
        RenderPipeline.repairWorldViewport(this, setViewport);
    }

    /**
     * {@code Blaze3dRenderTargetExt} member, added to {@link com.mojang.blaze3d.pipeline.RenderTarget}
     * by an Iris mixin at runtime. Declaring it here is a plain override as far as the JVM is
     * concerned and is inert when Iris is not installed, so ModernOPT needs neither a compile nor
     * a runtime dependency on Iris.
     */
    public int iris$getDepthBufferVersion() {
        return this.irisDepthBufferVersion;
    }

    /** Counterpart of {@link #iris$getDepthBufferVersion()} for the colour attachment. */
    public int iris$getColorBufferVersion() {
        return this.irisColorBufferVersion;
    }
}