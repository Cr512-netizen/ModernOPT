package net.cr512.modernopt.mixin;

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
        // During the initial transition the main target has not been swapped yet.
        // Use the dimensions selected for this phase, not the currently bound target,
        // otherwise a LevelRenderer.resize callback can immediately undo the scale.
        int width = RenderScalePhase.activeRenderWidth();
        int height = RenderScalePhase.activeRenderHeight();
        if (width > 0 && height > 0) {
            RenderTargetLifecycle.syncWorldTargets(minecraft, width, height);
        }
    }
}
