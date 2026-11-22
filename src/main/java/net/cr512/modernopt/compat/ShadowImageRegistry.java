// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.compat;

import net.cr512.modernopt.ModernOPT;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Names of the pipeline's custom images that the shadow pass itself writes.
 *
 * <p>Iris clears every custom image on each level frame. Holding the shadow map therefore also
 * has to hold the custom images the shadow pass feeds, but clearing an image the shadow pass did
 * not write leaves stale contents in unrelated targets and shows up as black patches. The set is
 * learned while Iris compiles the shadow programs: each custom-image uniform declared by such a
 * program is a name the shadow pass can write.</p>
 *
 * <p>The registry mirrors the safe part of the reference held-map design: only images the shadow
 * pass actually declares are preserved on reused frames, and the shadow composite chain is only
 * re-run when at least one such image exists.</p>
 */
public final class ShadowImageRegistry {

    /** Every custom image of the current pipeline, used to filter uniform lookups. */
    private static final Set<String> CUSTOM_NAMES = new LinkedHashSet<>();
    /** Custom images declared by at least one shadow program. */
    private static final Set<String> SHADOW_FED = new LinkedHashSet<>();

    private static final ThreadLocal<Integer> PROGRAM_DEPTH = new ThreadLocal<>();
    private static Object currentPipeline;
    private static boolean customNamesKnown;
    private static boolean learned;
    private static boolean scanLogged;
    private static boolean learnFailureLogged;

    private ShadowImageRegistry() {
    }

    /** Marks the start of one shadow shader compilation on this thread. */
    public static void beginShadowProgram(Object pipeline) {
        if (pipeline != currentPipeline) {
            currentPipeline = pipeline;
            CUSTOM_NAMES.clear();
            SHADOW_FED.clear();
            customNamesKnown = collectCustomNames(pipeline);
            learned = false;
            scanLogged = false;
        }
        Integer depth = PROGRAM_DEPTH.get();
        PROGRAM_DEPTH.set(depth == null ? 1 : depth + 1);
        if (customNamesKnown) {
            learned = true;
        }
    }

    /** Marks the end of one shadow shader compilation on this thread. */
    public static void endShadowProgram() {
        Integer depth = PROGRAM_DEPTH.get();
        if (depth == null || depth <= 1) {
            PROGRAM_DEPTH.remove();
            if (!scanLogged && Boolean.getBoolean("modernopt.bench")) {
                scanLogged = true;
                ModernOPT.LOG.info("[BENCH][SHADOWGL] shadow program image scan: {} custom "
                        + "images, {} shadow-fed", CUSTOM_NAMES.size(), SHADOW_FED.size());
            }
        } else {
            PROGRAM_DEPTH.set(depth - 1);
        }
    }

    /** A uniform lookup that resolved during shadow compilation; record custom image names. */
    public static void note(String name) {
        if (!inShadowProgram() || name == null || !CUSTOM_NAMES.contains(name)) {
            return;
        }
        if (SHADOW_FED.add(name) && Boolean.getBoolean("modernopt.bench")) {
            ModernOPT.LOG.info("[BENCH][SHADOWGL] shadow pass declares custom image {}; held with "
                    + "the depth map", name);
        }
    }

    public static boolean inShadowProgram() {
        Integer depth = PROGRAM_DEPTH.get();
        return depth != null && depth > 0;
    }

    /** True once the shadow programs of the current pipeline have been inspected. */
    public static boolean learned() {
        return learned;
    }

    /** True when at least one custom image is written by the shadow pass. */
    public static boolean feedsShadowImages() {
        return !SHADOW_FED.isEmpty();
    }

    /** True when this image name is written by the shadow pass and must survive a held frame. */
    public static boolean isShadowFed(String name) {
        return name != null && SHADOW_FED.contains(name);
    }

    /** Drops the learned set when Iris recreates its pipeline. */
    public static void clear() {
        CUSTOM_NAMES.clear();
        SHADOW_FED.clear();
        PROGRAM_DEPTH.remove();
        currentPipeline = null;
        customNamesKnown = false;
        learned = false;
        scanLogged = false;
    }

    private static boolean collectCustomNames(Object pipeline) {
        try {
            Field field = pipeline.getClass().getDeclaredField("customImages");
            field.setAccessible(true);
            Object value = field.get(pipeline);
            if (!(value instanceof Iterable<?> images)) {
                return false;
            }
            for (Object image : images) {
                if (image == null) {
                    continue;
                }
                Method getName = image.getClass().getMethod("getName");
                Object name = getName.invoke(image);
                if (name instanceof String text) {
                    CUSTOM_NAMES.add(text);
                }
            }
            return true;
        } catch (Throwable throwable) {
            if (!learnFailureLogged && Boolean.getBoolean("modernopt.bench")) {
                learnFailureLogged = true;
                ModernOPT.LOG.warn("[BENCH][SHADOWGL] could not inspect the pipeline's custom "
                        + "images", throwable);
            }
            return false;
        }
    }
}
