package net.cr512.modernopt.mixin;

import net.cr512.modernopt.client.PerformanceOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the optional status line after the HUD has finished, so it is never covered. */
@Mixin(Gui.class)
public abstract class GuiMixin {

    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("TAIL")
    )
    private void modernopt$overlay(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo callbackInfo) {
        PerformanceOverlay.render(guiGraphics);
    }
}
