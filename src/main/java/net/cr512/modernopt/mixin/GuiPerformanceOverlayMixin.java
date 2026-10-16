package net.cr512.modernopt.mixin;

import net.cr512.modernopt.client.PerformanceOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the optional ModernOPT performance HUD over the native-resolution GUI. */
@Mixin(Gui.class)
public abstract class GuiPerformanceOverlayMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void modernopt$renderPerformanceOverlay(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        PerformanceOverlay.render(graphics);
    }
}
