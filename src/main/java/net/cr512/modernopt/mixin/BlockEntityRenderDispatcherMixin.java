package net.cr512.modernopt.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.cr512.modernopt.render.WorldCpuProfiler;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bench-only block-entity CPU timer. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void modernopt$beginBlockEntity(BlockEntity blockEntity, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, CallbackInfo callbackInfo) {
        WorldCpuProfiler.beginBlockEntity();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void modernopt$endBlockEntity(BlockEntity blockEntity, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, CallbackInfo callbackInfo) {
        WorldCpuProfiler.endBlockEntity();
    }
}
