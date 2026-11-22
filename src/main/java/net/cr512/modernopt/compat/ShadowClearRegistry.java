// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.compat;

import net.cr512.modernopt.ModernOPT;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Identity registry for the shader pipeline's shadow clear passes.
 *
 * <p>Mixin classes may not expose non-private static helpers, so the shared state lives in this
 * plain class: the creator mixin tags the passes, the clear-pass mixin asks whether a pass has to
 * be skipped while the shadow map is held.</p>
 *
 * <p>The registry also guards the feature against an Iris rename: if the creator hook never ran,
 * the tags are missing and every held frame would clear the map again, producing flicker with no
 * error in the log. The first shadow frame after a pipeline rebuild verifies the registry and, if
 * it is empty, permanently disables the hold for that pipeline with one warning.</p>
 */
public final class ShadowClearRegistry {

    private static final Set<Object> SHADOW_PASSES =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private static boolean verified;
    private static boolean holdDisabled;

    private static java.lang.reflect.Method clearPassGetFramebuffer;
    private static java.lang.reflect.Method framebufferGetId;

    private ShadowClearRegistry() {
    }

    public static void tag(Object clearPass) {
        if (clearPass != null) {
            SHADOW_PASSES.add(clearPass);
        }
    }

    public static boolean isShadowPass(Object clearPass) {
        return clearPass != null && SHADOW_PASSES.contains(clearPass);
    }

    /**
     * Fallback for a pass that was not tagged: compare the framebuffer the pass clears with the
     * shadow depth-source framebuffer. Covers Iris renaming the creator method or recreating the
     * pass objects after a flip.
     */
    public static boolean clearsShadowFramebuffer(Object clearPass, int shadowFramebuffer) {
        if (clearPass == null || shadowFramebuffer <= 0) {
            return false;
        }
        try {
            if (clearPassGetFramebuffer == null) {
                clearPassGetFramebuffer = clearPass.getClass().getMethod("getFramebuffer");
            }
            Object framebuffer = clearPassGetFramebuffer.invoke(clearPass);
            if (framebuffer == null) {
                return false;
            }
            if (framebufferGetId == null) {
                framebufferGetId = framebuffer.getClass().getMethod("getId");
            }
            Object id = framebufferGetId.invoke(framebuffer);
            return id instanceof Integer && (Integer) id == shadowFramebuffer;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isEmpty() {
        return SHADOW_PASSES.isEmpty();
    }

    /** Number of tagged shadow clear passes, for the bench diagnostic. */
    public static int size() {
        return SHADOW_PASSES.size();
    }

    public static boolean holdDisabled() {
        return holdDisabled;
    }

    /**
     * Called once per pipeline generation, on the first shadow frame. An empty registry means
     * {@code createShadowClearPasses} is no longer the method Iris uses, so the hold cannot be
     * made safe and is disabled instead of silently flickering.
     */
    public static void verifyAfterPipelineLoad() {
        if (verified) {
            return;
        }
        verified = true;
        if (SHADOW_PASSES.isEmpty()) {
            holdDisabled = true;
            ModernOPT.LOG.warn("[ModernOPT] Iris shadow clear passes were not tagged "
                    + "(createShadowClearPasses returned nothing); shadow-map hold is disabled "
                    + "for this pipeline and the shadow interval behaves as 1");
        }
    }

    /** Drops passes from a destroyed pipeline; called when Iris recreates its shadow targets. */
    public static void clear() {
        SHADOW_PASSES.clear();
        verified = false;
        holdDisabled = false;
    }
}
