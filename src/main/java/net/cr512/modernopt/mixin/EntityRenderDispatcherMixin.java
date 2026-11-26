// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.mixin;

import net.cr512.modernopt.render.RenderPipeline;
import net.cr512.modernopt.render.WorldCpuProfiler;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times entity rendering for the CPU profiler and records the entity's screen-space motion for
 * the MSR2 temporal pass.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(
        method = "render",
        at = @At("HEAD")
    )
    private void modernopt$beginEntityTiming(Entity entity, double x, double y, double z, float yaw, float pitch, com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.MultiBufferSource multiBufferSource, int packedLight, CallbackInfo ci) {
        WorldCpuProfiler.beginEntity();
        RenderPipeline.recordEntityMotion(entity, x, y, z);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void modernopt$endEntityTiming(Entity entity, double x, double y, double z, float yaw, float pitch,
            com.mojang.blaze3d.vertex.PoseStack poseStack,
            net.minecraft.client.renderer.MultiBufferSource multiBufferSource, int packedLight,
            CallbackInfo ci) {
        WorldCpuProfiler.endEntity();
    }
}