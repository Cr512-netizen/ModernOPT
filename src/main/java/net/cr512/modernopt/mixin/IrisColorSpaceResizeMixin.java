package net.cr512.modernopt.mixin;

import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.render.RenderScaleController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Avoids an Iris 1.21.1 shader-program rebuild when only the color-space
 * converter output dimensions change. Reflection keeps Iris optional and
 * failures fall through to Iris' original implementation.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.colorspace.ColorSpaceFragmentConverter", remap = false)
public abstract class IrisColorSpaceResizeMixin {
    @Unique private static final Logger MODERNOPT_LOGGER = LoggerFactory.getLogger("ModernOPT");
    @Unique private static volatile boolean modernopt$reflectionResolved;
    @Unique private static boolean modernopt$warned;
    @Unique private static Field modernopt$widthField;
    @Unique private static Field modernopt$heightField;
    @Unique private static Field modernopt$colorSpaceField;
    @Unique private static Field modernopt$programField;
    @Unique private static Field modernopt$swapTextureField;
    @Unique private static Method modernopt$texImage2D;

    @Inject(method = "rebuildProgram", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void modernopt$resizeWithoutShaderRecompile(int width, int height, @Coerce Object colorSpace, CallbackInfo ci) {
        if (!ModernOptConfig.ENABLED.get() || !RenderScaleController.isEnabled() || width <= 0 || height <= 0) return;
        if (!modernopt$resolveReflection(this)) return;

        try {
            int oldWidth = modernopt$widthField.getInt(this);
            int oldHeight = modernopt$heightField.getInt(this);
            Object oldColorSpace = modernopt$colorSpaceField.get(this);
            Object program = modernopt$programField.get(this);
            if (program == null || !Objects.equals(oldColorSpace, colorSpace)
                || (oldWidth == width && oldHeight == height)) {
                return;
            }

            int texture = modernopt$swapTextureField.getInt(this);
            if (texture <= 0) return;

            modernopt$texImage2D.invoke(null, texture,
                org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11C.GL_RGBA8,
                width, height, 0, org.lwjgl.opengl.GL11C.GL_RGBA, org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE,
                (ByteBuffer) null);
            modernopt$widthField.setInt(this, width);
            modernopt$heightField.setInt(this, height);
            ci.cancel();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            modernopt$warnOnce("Could not use Iris' dimension-only color-space resize fast path; allowing Iris to rebuild normally.", exception);
        }
    }

    @Unique
    private static synchronized boolean modernopt$resolveReflection(Object instance) {
        if (modernopt$reflectionResolved) {
            return modernopt$widthField != null && modernopt$heightField != null
                && modernopt$colorSpaceField != null && modernopt$programField != null
                && modernopt$swapTextureField != null && modernopt$texImage2D != null;
        }
        modernopt$reflectionResolved = true;
        try {
            Class<?> type = instance.getClass();
            modernopt$widthField = modernopt$field(type, "width");
            modernopt$heightField = modernopt$field(type, "height");
            modernopt$colorSpaceField = modernopt$field(type, "colorSpace");
            modernopt$programField = modernopt$field(type, "program");
            modernopt$swapTextureField = modernopt$field(type, "swapTexture");
            Class<?> irisRenderSystem = Class.forName("net.irisshaders.iris.gl.IrisRenderSystem", false, type.getClassLoader());
            modernopt$texImage2D = irisRenderSystem.getMethod("texImage2D",
                int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class, ByteBuffer.class);
            modernopt$texImage2D.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            modernopt$widthField = null;
            modernopt$heightField = null;
            modernopt$colorSpaceField = null;
            modernopt$programField = null;
            modernopt$swapTextureField = null;
            modernopt$texImage2D = null;
            modernopt$warnOnce("Iris color-space resize fast path is unavailable on this Iris build; normal Iris resizing will be used.", exception);
        }
        return modernopt$widthField != null && modernopt$heightField != null
            && modernopt$colorSpaceField != null && modernopt$programField != null
            && modernopt$swapTextureField != null && modernopt$texImage2D != null;
    }

    @Unique
    private static Field modernopt$field(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    @Unique
    private static synchronized void modernopt$warnOnce(String message, Throwable cause) {
        if (modernopt$warned) return;
        modernopt$warned = true;
        MODERNOPT_LOGGER.warn(message, cause);
    }
}
