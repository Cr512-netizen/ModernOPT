package net.cr512.modernopt.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.cr512.modernopt.render.GpuProfiler;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bench-only GPU timers for the two halves of the world pass that dominate when a shader pack is
 * active: chunk layer rasterisation and the sky/clouds.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererTimingMixin {

    @Inject(method = "renderSectionLayer", at = @At("HEAD"))
    private void modernopt$beginTerrain(RenderType renderType, double x, double y, double z,
            Matrix4f modelView, Matrix4f projection, CallbackInfo callbackInfo) {
        GpuProfiler.begin(GpuProfiler.TERRAIN);
    }

    @Inject(method = "renderSectionLayer", at = @At("RETURN"))
    private void modernopt$endTerrain(RenderType renderType, double x, double y, double z,
            Matrix4f modelView, Matrix4f projection, CallbackInfo callbackInfo) {
        GpuProfiler.end(GpuProfiler.TERRAIN);
    }

    @Inject(method = "renderSky", at = @At("HEAD"))
    private void modernopt$beginSky(Matrix4f modelView, Matrix4f projection, float partialTick,
            Camera camera, boolean fog, Runnable setup, CallbackInfo callbackInfo) {
        GpuProfiler.begin(GpuProfiler.SKY);
    }

    @Inject(method = "renderSky", at = @At("RETURN"))
    private void modernopt$endSky(Matrix4f modelView, Matrix4f projection, float partialTick,
            Camera camera, boolean fog, Runnable setup, CallbackInfo callbackInfo) {
        GpuProfiler.end(GpuProfiler.SKY);
    }
}
