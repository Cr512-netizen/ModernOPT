// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Writes the real {@code mainRenderTarget} field.
 *
 * <p>The fast path points this field at ModernOPT's world target for the duration of
 * {@code renderLevel}, so every vanilla and modded caller gets the small target from the normal
 * getter without a per-call redirect. The field is restored before the hand, HUD and presentation
 * passes.</p>
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {

    @Mutable
    @Accessor("mainRenderTarget")
    void modernopt$setMainRenderTarget(RenderTarget target);

    @Accessor("mainRenderTarget")
    RenderTarget modernopt$getMainRenderTargetField();
}
