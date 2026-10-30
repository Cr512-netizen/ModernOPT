package net.cr512.modernopt.compat;

import java.lang.reflect.Method;

/**
 * Distant Horizons compatibility detection.
 * Detects if DH is loaded and provides utilities for LOD-aware rendering.
 */
public final class DistantHorizonsCompat {

    private static boolean probed;
    private static boolean loaded;

    private DistantHorizonsCompat() {
    }

    /** @return true if Distant Horizons mod is installed and active. */
    public static boolean isLoaded() {
        if (!probed) {
            probed = true;
            try {
                Class.forName("dev.skymc.distant_horizons.DistantHorizons", false, DistantHorizonsCompat.class.getClassLoader());
                loaded = true;
            } catch (Throwable ignored) {
                loaded = false;
            }
        }
        return loaded;
    }

    /**
     * Check if a given chunk coordinate is part of DH's LOD system.
     * LOD chunks are rendered at much lower detail and don't benefit from edge-aware sharpening.
     */
    public static boolean isLodChunk(int chunkX, int chunkZ) {
        if (!isLoaded()) return false;
        try {
            Class<?> dhApi = Class.forName("dev.skymc.distant_horizons.api.DistantHorizonsAPI");
            Method method = dhApi.getMethod("isLodChunk", int.class, int.class);
            return (Boolean) method.invoke(null, chunkX, chunkZ);
        } catch (Throwable ignored) {
            return false;
        }
    }
}