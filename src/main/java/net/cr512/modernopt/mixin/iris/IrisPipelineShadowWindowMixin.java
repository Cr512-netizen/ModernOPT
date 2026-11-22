// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.mixin.iris;

import net.cr512.modernopt.compat.ShadowImageRegistry;
import net.cr512.modernopt.render.RenderPipeline;
import org.lwjgl.opengl.ARBClearTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Brackets Iris' level-start window, where it clears the shadow targets directly before
 * {@code ShadowRenderer#renderShadows}. On a held frame the depth clear is suppressed by this
 * window even if the depth framebuffer id could not be resolved.
 *
 * <p>Optional and reflective: the mixin plugin skips it when Iris is absent, and {@code require=0}
 * keeps it inert for Iris versions without {@code beginLevelRendering}.</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPipelineShadowWindowMixin {

    private static boolean modernopt$shadowProgramLogged;

    @Inject(method = "beginLevelRendering()V", at = @At("HEAD"), require = 0, remap = false)
    private void modernopt$openShadowClearWindow(CallbackInfo callbackInfo) {
        RenderPipeline.beginShadowClearWindow();
    }

    @Inject(method = "beginLevelRendering()V", at = @At("RETURN"), require = 0, remap = false)
    private void modernopt$closeShadowClearWindow(CallbackInfo callbackInfo) {
        RenderPipeline.endShadowClearWindow();
    }

    /**
     * Bracket shadow shader compilation so the program's custom-image uniforms can be recorded.
     * Both overloads are targeted because Iris delegates from the {@code Optional} form to the
     * full form; the registry counts nesting and inspects the pipeline's image names once.
     */
    @Inject(
            method = "createShadowShader(Ljava/lang/String;Ljava/util/Optional;"
                    + "Lnet/irisshaders/iris/pipeline/programs/ShaderKey;)"
                    + "Lnet/minecraft/client/renderer/ShaderInstance;",
            at = @At("HEAD"),
            require = 0,
            remap = false
    )
    private void modernopt$beginOptionalShadowProgram(
            CallbackInfoReturnable<net.minecraft.client.renderer.ShaderInstance> callbackInfo) {
        if (Boolean.getBoolean("modernopt.bench") && !modernopt$shadowProgramLogged) {
            modernopt$shadowProgramLogged = true;
            net.cr512.modernopt.ModernOPT.LOG.info(
                    "[BENCH][SHADOWGL] shadow shader compilation hook reached");
        }
        ShadowImageRegistry.beginShadowProgram(this);
    }

    @Inject(
            method = "createShadowShader(Ljava/lang/String;Ljava/util/Optional;"
                    + "Lnet/irisshaders/iris/pipeline/programs/ShaderKey;)"
                    + "Lnet/minecraft/client/renderer/ShaderInstance;",
            at = @At("RETURN"),
            require = 0,
            remap = false
    )
    private void modernopt$endOptionalShadowProgram(
            CallbackInfoReturnable<net.minecraft.client.renderer.ShaderInstance> callbackInfo) {
        ShadowImageRegistry.endShadowProgram();
    }

    @Inject(
            method = "createShadowShader(Ljava/lang/String;"
                    + "Lnet/irisshaders/iris/shaderpack/programs/ProgramSource;"
                    + "Lnet/irisshaders/iris/shaderpack/loading/ProgramId;"
                    + "Lnet/irisshaders/iris/gl/blending/AlphaTest;"
                    + "Lcom/mojang/blaze3d/vertex/VertexFormat;ZZZZ)"
                    + "Lnet/minecraft/client/renderer/ShaderInstance;",
            at = @At("HEAD"),
            require = 0,
            remap = false
    )
    private void modernopt$beginFullShadowProgram(
            CallbackInfoReturnable<net.minecraft.client.renderer.ShaderInstance> callbackInfo) {
        ShadowImageRegistry.beginShadowProgram(this);
    }

    @Inject(
            method = "createShadowShader(Ljava/lang/String;"
                    + "Lnet/irisshaders/iris/shaderpack/programs/ProgramSource;"
                    + "Lnet/irisshaders/iris/shaderpack/loading/ProgramId;"
                    + "Lnet/irisshaders/iris/gl/blending/AlphaTest;"
                    + "Lcom/mojang/blaze3d/vertex/VertexFormat;ZZZZ)"
                    + "Lnet/minecraft/client/renderer/ShaderInstance;",
            at = @At("RETURN"),
            require = 0,
            remap = false
    )
    private void modernopt$endFullShadowProgram(
            CallbackInfoReturnable<net.minecraft.client.renderer.ShaderInstance> callbackInfo) {
        ShadowImageRegistry.endShadowProgram();
    }

    /**
     * Iris clears every custom image at level start with {@code glClearTexImage}. Shadow-fed
     * images are part of the held shadow result and must not be cleared on a reused frame; all
     * other custom images are cleared normally.
     */
    @Redirect(
            method = "beginLevelRendering()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/ARBClearTexture;glClearTexImage(IIII[I)V"
            ),
            require = 0,
            remap = false
    )
    private void modernopt$keepShadowImages(int texture, int level, int format, int type, int[] data) {
        int interval = Math.max(1, net.cr512.modernopt.config.ModernOptSettings.shadowUpdateInterval());
        if (RenderPipeline.shadowHoldShouldDraw(interval)) {
            ARBClearTexture.glClearTexImage(texture, level, format, type, data);
            return;
        }
        String name = modernopt$shadowImageName(texture);
        if (name == null) {
            ARBClearTexture.glClearTexImage(texture, level, format, type, data);
            return;
        }
        if (Boolean.getBoolean("modernopt.bench") && modernopt$keptImages.add(texture)) {
            net.cr512.modernopt.ModernOPT.LOG.info(
                    "[BENCH][SHADOWGL] kept shadow-fed custom image {} on a held frame", name);
        }
    }

    private static final java.util.Set<Integer> modernopt$keptImages = new java.util.HashSet<>();

    private static Field modernopt$clearImagesField;
    private static Method modernopt$imageId;
    private static Method modernopt$imageName;

    /** Name of a clear image that belongs to the held shadow result, or null to clear it. */
    private String modernopt$shadowImageName(int texture) {
        try {
            if (modernopt$clearImagesField == null) {
                modernopt$clearImagesField = getClass().getDeclaredField("clearImages");
                modernopt$clearImagesField.setAccessible(true);
            }
            Object value = modernopt$clearImagesField.get(this);
            if (!(value instanceof Object[] images)) {
                return null;
            }
            for (Object image : images) {
                if (image == null) {
                    continue;
                }
                if (modernopt$imageId == null) {
                    modernopt$imageId = image.getClass().getMethod("getId");
                    modernopt$imageName = image.getClass().getMethod("getName");
                }
                Object id = modernopt$imageId.invoke(image);
                if (id instanceof Integer && (Integer) id == texture) {
                    Object name = modernopt$imageName.invoke(image);
                    if (!(name instanceof String text)) {
                        return null;
                    }
                    if (ShadowImageRegistry.learned()) {
                        // Precise path: keep exactly the images the shadow pass writes. Clearing
                        // an unrelated image that merely has "shadow" in its name used to leave
                        // stale contents and produced black patches.
                        return ShadowImageRegistry.isShadowFed(text) ? text : null;
                    }
                    // Fallback for an Iris version without the program hook.
                    return text.toLowerCase(java.util.Locale.ROOT).contains("shadow") ? text : null;
                }
            }
        } catch (Throwable ignored) {
            // If the field/method shape changes, keep the original clear rather than guessing.
        }
        return null;
    }
}
