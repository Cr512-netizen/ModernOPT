package net.cr512.modernopt.mixin;

import net.cr512.modernopt.client.PerformanceOverlay;
import net.cr512.modernopt.render.RenderPipeline;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
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
        Minecraft minecraft = Minecraft.getInstance();
        // Vanilla built the HUD orthographic projection from Window#getWidth/Height a few lines
        // earlier. While the world is being rendered those report the internal (scaled) size, so
        // the projection ended up describing 320x180 GUI units instead of 640x360: the whole HUD
        // was drawn twice as large in the top-left corner and the hotbar/health fell off screen.
        // The real GUI size is cached separately (setGuiScale uses the framebuffer), so rebuild
        // the projection from it right before the HUD is submitted.
        if (minecraft.getWindow() != null && minecraft.getWindow().getGuiScale() > 0.0D) {
            int guiWidth = minecraft.getWindow().getGuiScaledWidth();
            int guiHeight = minecraft.getWindow().getGuiScaledHeight();
            if (guiWidth > 0 && guiHeight > 0) {
                Matrix4f ortho = new Matrix4f().setOrtho(0.0F, (float) guiWidth, (float) guiHeight, 0.0F,
                        1000.0F, net.neoforged.neoforge.client.ClientHooks.getGuiFarPlane());
                com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(
                        ortho, com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);
            }
        }
    }

    @Inject(
            method = "renderItemInHand(Lnet/minecraft/client/Camera;FLorg/joml/Matrix4f;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;"
                            + "renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"
                            + "Lnet/minecraft/client/player/LocalPlayer;I)V"
            )
    )
    private void modernopt$renderDeferredHand(Camera camera, float partialTick, Matrix4f pose,
            CallbackInfo callbackInfo,
            @com.llamalad7.mixinextras.sugar.Local PoseStack poseStack) {
        RenderPipeline.renderDeferredHand((GameRenderer) (Object) this, camera, partialTick, poseStack);
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
