package net.cr512.modernopt.mixin;

import net.cr512.modernopt.render.RenderScalePhase;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ensures screens always render with the native GUI/window dimensions. */
@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void modernopt$restoreNativeGuiScale(CallbackInfo ci) {
        RenderScalePhase.end();
    }
}
