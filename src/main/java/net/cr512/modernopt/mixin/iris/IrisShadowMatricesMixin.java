// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.mixin.iris;

import com.mojang.blaze3d.vertex.PoseStack;
import net.cr512.modernopt.render.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Anchors the shadow lookup matrix to the frame that actually drew the held map.
 *
 * <p>Iris rebuilds {@code shadowModelView} every frame from the current camera, including the
 * sub-block snap offset. Within one snap cell that is equivalent to translating the held matrix,
 * but it is not when the camera crosses a cell boundary or the pack uses a snap size other than
 * one: the lookup then jumps by a whole shadow-map cell while the map itself is still anchored in
 * the previous cell. On a held frame the camera arguments are therefore replaced with the camera
 * of the drawn frame and the resulting pose is translated by the current delta after the snap.
 * This mirrors the matrix source the reference held-map design keeps stable.</p>
 *
 * <p>Only active while {@link RenderPipeline#shadowHoldSkipFrame()} is set for the frame, so
 * interval 1 and every drawn frame use Iris' own matrix unchanged.</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowMatrices", remap = false)
public abstract class IrisShadowMatricesMixin {

    private static float modernopt$heldAngle;
    private static double modernopt$heldX;
    private static double modernopt$heldY;
    private static double modernopt$heldZ;
    private static double modernopt$deltaX;
    private static double modernopt$deltaY;
    private static double modernopt$deltaZ;
    private static boolean modernopt$haveHeld;
    private static boolean modernopt$translationLogged;

    private static final String modernopt$CREATE =
            "createModelViewMatrix(Lcom/mojang/blaze3d/vertex/PoseStack;FFFDDDFF)V";

    /**
     * The shadow map was rasterised with the sun angle of the drawn frame. Keeping that angle for
     * the lookup while the map is reused prevents the shadow pattern from rotating with the live
     * sun and snapping back on every rebuild.
     */
    @ModifyVariable(method = modernopt$CREATE, at = @At("HEAD"), argsOnly = true,
            ordinal = 0, require = 0, remap = false)
    private static float modernopt$holdAngle(float shadowAngle) {
        if (RenderPipeline.shadowHoldSkipFrame() && modernopt$haveHeld) {
            return modernopt$heldAngle;
        }
        modernopt$heldAngle = shadowAngle;
        return shadowAngle;
    }

    @ModifyVariable(method = modernopt$CREATE, at = @At("HEAD"), argsOnly = true,
            ordinal = 0, require = 0, remap = false)
    private static double modernopt$holdX(double x) {
        if (RenderPipeline.shadowHoldSkipFrame() && modernopt$haveHeld) {
            modernopt$deltaX = x - modernopt$heldX;
            return modernopt$heldX;
        }
        modernopt$heldX = x;
        modernopt$deltaX = 0.0;
        modernopt$haveHeld = true;
        return x;
    }

    @ModifyVariable(method = modernopt$CREATE, at = @At("HEAD"), argsOnly = true,
            ordinal = 1, require = 0, remap = false)
    private static double modernopt$holdY(double y) {
        if (RenderPipeline.shadowHoldSkipFrame() && modernopt$haveHeld) {
            modernopt$deltaY = y - modernopt$heldY;
            return modernopt$heldY;
        }
        modernopt$heldY = y;
        modernopt$deltaY = 0.0;
        return y;
    }

    @ModifyVariable(method = modernopt$CREATE, at = @At("HEAD"), argsOnly = true,
            ordinal = 2, require = 0, remap = false)
    private static double modernopt$holdZ(double z) {
        if (RenderPipeline.shadowHoldSkipFrame() && modernopt$haveHeld) {
            modernopt$deltaZ = z - modernopt$heldZ;
            return modernopt$heldZ;
        }
        modernopt$heldZ = z;
        modernopt$deltaZ = 0.0;
        return z;
    }

    @Inject(method = modernopt$CREATE, at = @At("RETURN"), require = 0, remap = false)
    private static void modernopt$translateHeldPose(
            PoseStack poseStack,
            float shadowAngle,
            float intervalSize,
            float sunPathRotation,
            double x,
            double y,
            double z,
            float nearPlane,
            float farPlane,
            CallbackInfo callbackInfo) {
        if (RenderPipeline.shadowHoldSkipFrame() && modernopt$haveHeld
                && (modernopt$deltaX != 0.0 || modernopt$deltaY != 0.0 || modernopt$deltaZ != 0.0)) {
            if (Boolean.getBoolean("modernopt.bench") && !modernopt$translationLogged) {
                modernopt$translationLogged = true;
                net.cr512.modernopt.ModernOPT.LOG.info(
                        "[BENCH][SHADOWGL] held shadow matrix translated by ({}, {}, {})",
                        modernopt$deltaX, modernopt$deltaY, modernopt$deltaZ);
            }
            poseStack.translate(modernopt$deltaX, modernopt$deltaY, modernopt$deltaZ);
        }
    }
}
