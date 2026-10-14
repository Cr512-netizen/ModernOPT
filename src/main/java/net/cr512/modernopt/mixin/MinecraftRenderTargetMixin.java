package net.cr512.modernopt.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Minecraft.class)
public abstract class MinecraftRenderTargetMixin {
    @Shadow @Final @Mutable private RenderTarget mainRenderTarget;
    @Unique public void modernopt$setMainRenderTarget(RenderTarget target) {
        this.mainRenderTarget = target;
    }
}
