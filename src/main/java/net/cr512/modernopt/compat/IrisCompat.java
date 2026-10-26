package net.cr512.modernopt.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Optional Iris bridge. Iris is never a compile dependency: everything here is reflective, so
 * the mod loads the same way with or without it.
 *
 * <p>With a shader pack active the world is still rendered into ModernOPT's scaled target
 * (Iris sizes its gbuffers from {@code Minecraft#getMainRenderTarget()}), but Iris owns the
 * scene depth buffer. FSR 2 must reproject against that depth, not against the scaled target's
 * own attachment, which the final fullscreen pass never writes. The accessors below reach the
 * pipeline's {@code RenderTargets} reflectively and return {@code -1} when the Iris version does
 * not expose the expected shape.</p>
 */
public final class IrisCompat {

    private static boolean probed;
    private static Method isShaderPackInUse;

    private static boolean managerProbed;
    private static Object pipelineManager;
    private static Method getPipelineNullable;
    private static Field renderTargetsField;
    private static Method getDepthTexture;

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

    /**
     * GL texture id of the depth buffer Iris rendered the current world frame into, or
     * {@code -1} when it cannot be reached. The texture is single sampled and lives at the
     * scaled render resolution, which is exactly what the temporal pass needs.
     */
    public static int depthTextureId() {
        probePipeline();
        if (pipelineManager == null || getPipelineNullable == null
                || renderTargetsField == null || getDepthTexture == null) {
            return -1;
        }
        try {
            Object pipeline = getPipelineNullable.invoke(pipelineManager);
            if (pipeline == null) {
                return -1;
            }
            Object targets = renderTargetsField.get(pipeline);
            if (targets == null) {
                return -1;
            }
            Object result = getDepthTexture.invoke(targets);
            return result instanceof Integer id && id > 0 ? id : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void probePipeline() {
        try {
            if (!managerProbed) {
                managerProbed = true;
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                Method getPipelineManager = iris.getMethod("getPipelineManager");
                getPipelineManager.setAccessible(true);
                pipelineManager = getPipelineManager.invoke(null);
            }
            if (pipelineManager == null) {
                return;
            }
            if (getPipelineNullable == null) {
                getPipelineNullable = pipelineManager.getClass().getMethod("getPipelineNullable");
                getPipelineNullable.setAccessible(true);
            }
            if (renderTargetsField != null && getDepthTexture != null) {
                return;
            }

            Object pipeline = getPipelineNullable.invoke(pipelineManager);
            if (pipeline == null) {
                // The pack may still be compiling. Try again on the next frame.
                return;
            }
            Field field;
            try {
                field = pipeline.getClass().getDeclaredField("renderTargets");
            } catch (NoSuchFieldException missing) {
                field = pipeline.getClass().getField("renderTargets");
            }
            field.setAccessible(true);
            Object targets = field.get(pipeline);
            if (targets == null) {
                return;
            }
            renderTargetsField = field;
            getDepthTexture = targets.getClass().getMethod("getDepthTexture");
            getDepthTexture.setAccessible(true);
        } catch (Throwable ignored) {
            pipelineManager = null;
            getPipelineNullable = null;
            renderTargetsField = null;
            getDepthTexture = null;
        }
    }
}
