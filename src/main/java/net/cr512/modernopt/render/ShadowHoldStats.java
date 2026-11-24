// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.render;

/**
 * One shared snapshot of the shadow-hold statistics.
 *
 * <p>The numerator and denominator always come from the same window and the configured interval
 * is part of the snapshot, so a line like {@code held 552/1661 (33%) interval=2} can be read
 * without guessing which mode produced it.</p>
 */
public record ShadowHoldStats(int drawn, int held, int total, double interval,
                              int forcedCamera, int forcedTime, int forcedCaster) {

    public double drawnShare() {
        return this.total <= 0 ? 1.0 : (double) this.drawn / this.total;
    }

    public double heldShare() {
        return this.total <= 0 ? 0.0 : (double) this.held / this.total;
    }
}
