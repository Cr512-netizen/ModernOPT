// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.mixin.iris;

import net.cr512.modernopt.render.RenderPipeline;
import org.lwjgl.opengl.ARBClearTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

    @Inject(method = "beginLevelRendering()V", at = @At("HEAD"), require = 0, remap = false)
    private void modernopt$openShadowClearWindow(CallbackInfo callbackInfo) {
        RenderPipeline.beginShadowClearWindow();
    }

    @Inject(method = "beginLevelRendering()V", at = @At("RETURN"), require = 0, remap = false)
    private void modernopt$closeShadowClearWindow(CallbackInfo callbackInfo) {
        RenderPipeline.endShadowClearWindow();
    }

    /**
     * Iris clears every custom image at level start with {@code glClearTexImage}. Shadow colour
     * images are among them; clearing those on a held frame removes the shadow-composite result
     * while the depth map is deliberately reused. Skip only images whose name identifies them as
     * shadow images, leaving all other custom images untouched.
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
        if (RenderPipeline.shadowHoldShouldDraw(interval) || !modernopt$isShadowImage(texture)) {
            ARBClearTexture.glClearTexImage(texture, level, format, type, data);
        }
    }

    private static Field modernopt$clearImagesField;
    private static Method modernopt$imageId;
    private static Method modernopt$imageName;

    private boolean modernopt$isShadowImage(int texture) {
        try {
            if (modernopt$clearImagesField == null) {
                modernopt$clearImagesField = getClass().getDeclaredField("clearImages");
                modernopt$clearImagesField.setAccessible(true);
            }
            Object value = modernopt$clearImagesField.get(this);
            if (!(value instanceof Object[] images)) {
                return false;
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
                    return name != null && name.toString().toLowerCase(java.util.Locale.ROOT).contains("shadow");
                }
            }
        } catch (Throwable ignored) {
            // If the field/method shape changes, keep the original clear rather than guessing.
        }
        return false;
    }
}
