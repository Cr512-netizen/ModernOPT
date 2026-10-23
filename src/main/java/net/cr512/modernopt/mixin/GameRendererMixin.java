package net.cr512.modernopt.mixin;

import net.cr512.modernopt.render.RenderPipeline;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bracket the world render pass.
 *
 * <p>{@code renderLevel} is entered after {@code GameRenderer#render} has already set the window
 * viewport, so binding the scaled target here means the whole world image lands in it.
 * The second injection fires right before the HUD is drawn, which is exactly where the image has
 * to be reconstructed into the real main render target.</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
    private void modernopt$beginWorldFrame(DeltaTracker deltaTracker, CallbackInfo callbackInfo) {
        RenderPipeline.beginWorldFrame();
    }

    @Inject(
            method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Gui;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void modernopt$endWorldFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callbackInfo) {
        RenderPipeline.endWorldFrame();
    }
}
