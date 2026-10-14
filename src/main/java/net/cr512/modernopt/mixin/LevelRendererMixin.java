package net.cr512.modernopt.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.cr512.modernopt.render.RenderScalePhase;
import net.cr512.modernopt.render.RenderTargetLifecycle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Handles auxiliary targets created or resized after the main render pass has
 * entered its temporary resolution phase.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "initOutline", at = @At("RETURN"))
    private void modernopt$syncTargetsAfterOutlineInit(CallbackInfo ci) {
        modernopt$syncTargetsIfScaling();
    }

    @Inject(method = "resize", at = @At("RETURN"))
    private void modernopt$syncTargetsAfterResize(CallbackInfo ci) {
        modernopt$syncTargetsIfScaling();
    }

    private void modernopt$syncTargetsIfScaling() {
        if (!RenderScalePhase.isActive()) return;
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget activeTarget = minecraft.getMainRenderTarget();
        RenderTargetLifecycle.syncWorldTargets(minecraft, activeTarget.width, activeTarget.height);
    }
}
