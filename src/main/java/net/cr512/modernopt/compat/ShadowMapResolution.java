// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.compat;

import net.cr512.modernopt.ModernOPT;
import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.config.ModernOptSettings;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

/**
 * Absolute shadow map resolution for Iris shader packs.
 *
 * <p>Packs usually hardcode the shadow map size (for example
 * {@code const int shadowMapResolution = 2048;} and Iris derives the size of the shadow
 * framebuffer from it). That size is completely independent of the render scale, so a 50% render
 * scale still pays for a 2048x2048 shadow pass — often several milliseconds per frame.</p>
 *
 * <p>Two hooks cooperate so that the buffer and the shader math never disagree:</p>
 * <ul>
 *   <li>{@code IrisShadowRenderTargetsMixin} replaces the resolution Iris passes to
 *       {@code ShadowRenderTargets}, which is what actually allocates the depth texture and the
 *       framebuffer and defines the shadow viewport.</li>
 *   <li>{@link net.cr512.modernopt.render.ShadowResolutionPatcher} rewrites the
 *       {@code shadowMapResolution} constant in the pack source, so texel math, blur radii and
 *       PCF offsets are computed for the real map size.</li>
 * </ul>
 *
 * <p>Both only run while a pack is being loaded, never per frame.</p>
 */
public final class ShadowMapResolution {

    /** Values offered in the config and the options screen. */
    private static final int[] ALLOWED = {512, 1024, 2048, 4096};

    /** Resolution the current shadow framebuffer was created with, {@code -1} when unknown. */
    private static volatile int applied = -1;
    /** Resolution the shader pack itself asked for, {@code -1} when unknown. */
    private static volatile int packRequested = -1;
    private static int loggedDesired = -1;
    private static int loggedPack = -1;
    private static volatile int patchedFor = -1;
    private static int reloadRequestedFor = -1;
    private static int fileValue = Integer.MIN_VALUE;
    private static Boolean fileEnabled;
    private static volatile boolean reloadPending;

    private ShadowMapResolution() {
    }

    public static int[] allowedValues() {
        return ALLOWED.clone();
    }

    /** Snaps an arbitrary config value to the closest supported resolution. */
    public static int snap(int value) {
        int best = ALLOWED[0];
        int bestDistance = Integer.MAX_VALUE;
        for (int candidate : ALLOWED) {
            int distance = Math.abs(candidate - value);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** The resolution the player asked for, snapped to a supported value. */
    public static int desired() {
        return snap(configuredResolution());
    }

    /**
     * Reads the config directly.
     *
     * <p>This is a cold path (pack load, options screen), and the per-frame snapshot in
     * {@link ModernOptSettings} may not have been refreshed yet when the shader pack is loaded on
     * startup, so the authoritative config value is used here.</p>
     */
    private static int configuredResolution() {
        try {
            if (ModernOptConfig.SPEC.isLoaded()) {
                return ModernOptConfig.SHADOW_MAP_RESOLUTION.get();
            }
        } catch (RuntimeException ignored) {
            // Config not available (early startup/reload): read the file directly below.
        }
        return resolutionFromConfigFile();
    }

    /**
     * Reads the resolution straight from the config file.
     *
     * <p>Iris preprocesses the shader pack very early on startup, before NeoForge has loaded the
     * config spec, so the pack would otherwise be compiled with the default value and need a
     * reload. Parsing the one integer we care about avoids that extra pack reload.</p>
     */
    private static int resolutionFromConfigFile() {
        if (fileValue != Integer.MIN_VALUE) {
            return fileValue;
        }
        int parsed = 2048;
        try {
            java.nio.file.Path path = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("modernopt-client.toml");
            if (java.nio.file.Files.isReadable(path)) {
                for (String line : java.nio.file.Files.readAllLines(path)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("shadowMapResolution")) {
                        int equals = trimmed.indexOf('=');
                        if (equals >= 0) {
                            parsed = Integer.parseInt(trimmed.substring(equals + 1).trim());
                        }
                        break;
                    }
                }
            }
        } catch (Throwable throwable) {
            ModernOPT.LOG.warn("[ModernOPT] could not read the shadow map resolution from the config, using 2048", throwable);
        }
        fileValue = parsed;
        return parsed;
    }

    /** Reads the master switch from the file; used before the config spec exists at startup. */
    private static boolean enabledFromConfigFile() {
        if (fileEnabled != null) {
            return fileEnabled;
        }
        boolean parsed = true;
        try {
            java.nio.file.Path path = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("modernopt-client.toml");
            if (java.nio.file.Files.isReadable(path)) {
                String section = "";
                for (String line : java.nio.file.Files.readAllLines(path)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("[")) {
                        section = trimmed;
                    } else if ((section.isEmpty() || section.equals("[general]"))
                            && trimmed.startsWith("enabled")) {
                        int equals = trimmed.indexOf('=');
                        if (equals >= 0) {
                            parsed = Boolean.parseBoolean(trimmed.substring(equals + 1).trim());
                        }
                        break;
                    }
                }
            }
        } catch (Throwable throwable) {
            ModernOPT.LOG.warn("[ModernOPT] could not read the master switch from the config, assuming enabled", throwable);
        }
        fileEnabled = parsed;
        return parsed;
    }

    /** Drops the file cache after NeoForge reloads the config spec. */
    public static void invalidateFileCache() {
        fileValue = Integer.MIN_VALUE;
        fileEnabled = null;
    }

    /** True when ModernOPT may override the pack's shadow map (master switch is on). */
    public static boolean enabled() {
        return overridesEnabled();
    }

    private static boolean overridesEnabled() {
        try {
            if (ModernOptConfig.SPEC.isLoaded()) {
                return ModernOptConfig.ENABLED.get();
            }
        } catch (RuntimeException ignored) {
            // Fall through to the file/snapshot.
        }
        return enabledFromConfigFile();
    }

    /**
     * Decides the resolution Iris should use for its shadow targets.
     *
     * <p>Called from {@code ShadowRenderTargets#<init>}, i.e. once per shader pack load. Returns
     * the pack's own value untouched when ModernOPT is disabled.</p>
     *
     * @param packValue resolution the shader pack requested
     * @return the resolution to allocate
     */
    public static int apply(int packValue) {
        if (!overridesEnabled()) {
            applied = packValue;
            packRequested = packValue;
            // The pack is back on its own value; forget the patched/reload bookkeeping so that
            // re-enabling the mod patches and reloads again instead of assuming the shaders match.
            patchedFor = packValue;
            reloadRequestedFor = -1;
            loggedDesired = -1;
            loggedPack = -1;
            return packValue;
        }
        int desired = desired();
        packRequested = packValue;
        applied = desired;
        if (loggedDesired != desired || loggedPack != packValue) {
            loggedDesired = desired;
            loggedPack = packValue;
            ModernOPT.LOG.info("[ModernOPT] shadow map {}x{} (shader pack requested {})",
                    desired, desired, packValue);
        }
        if (desired != packValue && patchedFor != desired && reloadRequestedFor != desired) {
            // The pack was preprocessed before the config was available (Iris loads it very early
            // on startup), so the shader constant still carries the pack's own value. One reload
            // re-runs the preprocessing with the correct config and keeps shader and buffer in
            // sync; it happens at most once per requested resolution.
            reloadRequestedFor = desired;
            ModernOPT.LOG.info("[ModernOPT] shadow map and shader constant disagree; reloading the "
                    + "shader pack once to synchronise them");
            requestReload();
        }
        return desired;
    }

    /** Resolution the live shadow map uses, or {@code -1} before the first pack load. */
    public static int applied() {
        return applied;
    }

    /** Resolution the shader pack asked for, or {@code -1} before the first pack load. */
    public static int packRequested() {
        return packRequested;
    }

    /** Recorded by the source patcher so {@link #apply(int)} knows the shaders match. */
    public static void notePatched(int resolution) {
        patchedFor = resolution;
    }

    /** Short description for the GPU profiling line. */
    public static String describe() {
        int size = applied;
        return size > 0 ? size + "px" : "pack";
    }

    /** True when the config no longer matches the shadow map currently in use. */
    public static boolean needsReload() {
        return enabled() && applied > 0 && desired() != applied;
    }

    /**
     * Reloads the shader pack so a changed resolution takes effect.
     *
     * <p>Reloading rebuilds every Iris target and recompiles the programs, so it is only ever
     * triggered by an explicit setting change (options screen or config file), never per frame.</p>
     */
    public static void requestReload() {
        if (reloadPending || !ModList.get().isLoaded("iris")) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        reloadPending = true;
        minecraft.execute(() -> {
            reloadPending = false;
            try {
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                Object manager = iris.getMethod("getPipelineManager").invoke(null);
                Object pipeline = manager.getClass().getMethod("getPipelineNullable").invoke(manager);
                if (pipeline == null) {
                    // No pack loaded: the value is picked up on the next pack load anyway.
                    return;
                }
                ModernOPT.LOG.info("[ModernOPT] reloading shader pack to {}",
                        enabled() ? "apply shadow map " + desired() : "restore the pack's shadow map");
                iris.getMethod("reload").invoke(null);
            } catch (Throwable throwable) {
                ModernOPT.LOG.warn("[ModernOPT] could not reload the shader pack after the shadow "
                        + "map change; it will apply on the next pack reload", throwable);
            }
        });
    }
}
