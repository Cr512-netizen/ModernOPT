// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.pipeline.RenderTarget;

import net.cr512.modernopt.render.RenderPipeline;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Answers with the downscaled HUD target while the GUI pass is active.
 *
 * <p>The world target is installed by writing the real {@code Minecraft.mainRenderTarget} field,
 * so this hook only covers the optional HUD scaling. It is a {@link ModifyReturnValue} rather
 * than an {@code @Inject}: the getter is called thousands of times per frame by Sodium and Iris,
 * and an injected callback would allocate a {@code CallbackInfoReturnable} on every call.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @ModifyReturnValue(method = "getMainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;",
            at = @At("RETURN"))
    private RenderTarget modernopt$hudTarget(RenderTarget original) {
        RenderTarget hud = RenderPipeline.activeHudTarget();
        if (hud != null) {
            return hud;
        }
        RenderTarget world = RenderPipeline.redirectedWorldTarget();
        return world != null ? world : original;
    }
}
