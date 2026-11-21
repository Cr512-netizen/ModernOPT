// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.client;

import net.cr512.modernopt.ModernOPT;
import net.cr512.modernopt.compat.ShadowMapResolution;
import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.config.ModernOptSettings;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;

/**
 * Applies a changed shadow map resolution.
 *
 * <p>Iris allocates its shadow targets when a pack is loaded, so editing the config file (or the
 * options screen) has to trigger one pack reload — and only one, never a rebuild per frame.</p>
 */
@EventBusSubscriber(modid = ModernOPT.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ShadowMapConfigEvents {

    private ShadowMapConfigEvents() {
    }

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != ModernOptConfig.SPEC) {
            return;
        }
        ShadowMapResolution.invalidateFileCache();
        ModernOptSettings.refresh();
        if (ShadowMapResolution.needsReload()) {
            ShadowMapResolution.requestReload();
        }
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.execute(net.cr512.modernopt.render.RenderPipeline::reloadShaders);
        }
    }
}
