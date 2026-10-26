package net.cr512.modernopt.mixin;

import net.cr512.modernopt.client.PerformanceOverlay;
import net.cr512.modernopt.render.RenderPipeline;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
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

    /**
     * Adds the FSR 2 sub-pixel jitter to the projection matrix handed to the world renderer.
     * Only the argument is replaced: the local matrix used later for the hand pass stays
     * untouched, and the hand pass resets the projection in any case.
     */
    @ModifyArg(
            method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"
            ),
            index = 6
    )
    private Matrix4f modernopt$jitterProjection(Matrix4f projection) {
        return RenderPipeline.jitterProjection(projection, ((GameRenderer) (Object) this).getMainCamera());
    }

    /**
     * FSR 2 must finish before vanilla clears the depth buffer for the hand pass. At this point
     * the world (including the NeoForge AFTER_LEVEL stage) is complete and the depth buffer still
     * belongs to the scaled target. Every other mode keeps finishing before the HUD.
     */
    @Inject(
            method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void modernopt$finishBeforeHand(DeltaTracker deltaTracker, CallbackInfo callbackInfo) {
        RenderPipeline.finishBeforeHand();
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

    /**
     * Draws the status line at the very end of the GUI pass. The F3 debug screen renders through
     * {@code GuiGraphics#drawManaged} and is still batched when {@code Gui#render} returns, so a
     * hook there ends up underneath it. By the time the final {@code flush()} in
     * {@code GameRenderer#render} has run, every HUD and debug element is already on screen; the
     * GUI projection and model view are still bound, so a fresh {@link GuiGraphics} can draw on
     * top of all of it.
     */
    @Inject(
            method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;flush()V",
                    shift = At.Shift.AFTER
            )
    )
    private void modernopt$statusOverlay(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callbackInfo) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        PerformanceOverlay.render(new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource()));
    }
}
