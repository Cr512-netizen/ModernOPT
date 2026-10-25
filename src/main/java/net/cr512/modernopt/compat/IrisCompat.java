package net.cr512.modernopt.compat;

import java.lang.reflect.Method;

/**
 * Optional Iris bridge. Iris is never a compile dependency: everything here is reflective, so
 * the mod loads the same way with or without it.
 *
 * <p>ModernOPT deliberately steps aside while a shader pack is in use. Iris builds its own
 * gbuffer/depth pipeline around {@code Minecraft#getMainRenderTarget()}; interposing a
 * multisampled or rescaled target on top of it produces broken shadows and ghosting, so the
 * honest behaviour is to keep vanilla/Iris output untouched and say so in the log.</p>
 */
public final class IrisCompat {

    private static boolean probed;
    private static Method isShaderPackInUse;

    private IrisCompat() {
    }

    public static boolean isLoaded() {
        try {
            Class.forName("net.irisshaders.iris.Iris", false, IrisCompat.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True when Iris is installed and a shader pack is currently enabled. */
    public static boolean isShaderPackInUse() {
        if (!probed) {
            probed = true;
            try {
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                // Iris renamed this method over time: 1.8.x exposes isPackInUseQuick(),
                // older builds used isShaderPackInUse(). Accept either.
                for (String name : new String[] {"isShaderPackInUse", "isPackInUseQuick"}) {
                    try {
                        Method method = iris.getMethod(name);
                        method.setAccessible(true);
                        isShaderPackInUse = method;
                        break;
                    } catch (NoSuchMethodException ignored) {
                        // try the next name
                    }
                }
            } catch (Throwable ignored) {
                isShaderPackInUse = null;
            }
        }
        if (isShaderPackInUse == null) {
            return false;
        }
        try {
            Object result = isShaderPackInUse.invoke(null);
            return result instanceof Boolean value && value;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
