// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.mixin;

import net.cr512.modernopt.render.WorldCpuProfiler;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times entity rendering for the CPU profiler.
 *
 * <p>The old per-entity motion-vector projection was removed together with the SSBO: the MSR2
 * temporal shader is camera-only and never read those vectors, so the hook only added a matrix
 * multiply per entity.</p>
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(
        method = "render",
        at = @At("HEAD")
    )
    private void modernopt$beginEntityTiming(Entity entity, double x, double y, double z, float yaw, float pitch, com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.MultiBufferSource multiBufferSource, int packedLight, CallbackInfo ci) {
        WorldCpuProfiler.beginEntity();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void modernopt$endEntityTiming(Entity entity, double x, double y, double z, float yaw, float pitch,
            com.mojang.blaze3d.vertex.PoseStack poseStack,
            net.minecraft.client.renderer.MultiBufferSource multiBufferSource, int packedLight,
            CallbackInfo ci) {
        WorldCpuProfiler.endEntity();
    }
}