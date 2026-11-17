package net.cr512.modernopt.mixin.iris;

import net.cr512.modernopt.compat.ShadowClearRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Remembers which clear passes belong to the shadow pipeline.
 *
 * <p>The shadow map is not a single framebuffer: depth, colour and translucent buffers each get
 * their own clear pass, and they do not share one GL id. Tagging the passes when the pipeline
 * creates them lets the hold logic skip exactly those clears instead of matching framebuffer ids,
 * which missed some of them and made the held map disappear every few frames.</p>
 */
@Mixin(targets = "net.irisshaders.iris.targets.ClearPassCreator", remap = false)
public abstract class IrisClearPassCreatorMixin {

    @Inject(method = "createShadowClearPasses", at = @At("RETURN"), require = 0, remap = false)
    private static void modernopt$tagShadowClearPasses(CallbackInfoReturnable<List<Object>> callbackInfo) {
        List<?> passes = callbackInfo.getReturnValue();
        if (passes == null) {
            return;
        }
        for (Object pass : passes) {
            ShadowClearRegistry.tag(pass);
        }
    }
}
