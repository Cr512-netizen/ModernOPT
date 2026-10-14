package net.cr512.modernopt.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {
    @Accessor("frameBufferId")
    int modernopt$getFrameBufferId();

    @Accessor("frameBufferId")
    void modernopt$setFrameBufferId(int value);

    @Accessor("colorTextureId")
    void modernopt$setColorTextureId(int value);

    @Accessor("depthBufferId")
    void modernopt$setDepthBufferId(int value);
}
