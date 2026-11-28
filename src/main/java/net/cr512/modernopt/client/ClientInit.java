// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

package net.cr512.modernopt.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.cr512.modernopt.ModernOPT;
import net.cr512.modernopt.FpsLogger;
import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.config.ModernOptSettings;
import net.cr512.modernopt.config.UpscalerType;
import net.cr512.modernopt.compat.ShadowMapResolution;
import net.cr512.modernopt.render.GpuProfiler;
import net.cr512.modernopt.render.RenderPipeline;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/** Game-bus client hooks: the options keybind. */
@EventBusSubscriber(modid = ModernOPT.MOD_ID, value = Dist.CLIENT)
public final class ClientInit {

    private static final KeyMapping OPEN_CONFIG = new KeyMapping("key.modernopt.config",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, "key.categories.misc");
    private static final KeyMapping CYCLE_UPSCALER = new KeyMapping("key.modernopt.cycleUpscaler",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, "key.categories.misc");

    private ClientInit() {
    }

    public static KeyMapping openConfigKey() {
        return OPEN_CONFIG;
    }

    public static KeyMapping cycleUpscalerKey() {
        return CYCLE_UPSCALER;
    }

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        ShadowMapResolution.tick();
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null) {
            return;
        }
        boolean pressed = false;
        while (OPEN_CONFIG.consumeClick()) {
            pressed = true;
        }
        if (pressed) {
            minecraft.setScreen(new ModernOptConfigScreen(null));
        }
        boolean cycle = false;
        while (CYCLE_UPSCALER.consumeClick()) {
            cycle = true;
        }
        if (cycle) {
            UpscalerType next = ModernOptSettings.upscaler().next();
            ModernOptConfig.UPSCALER.set(next);
            ModernOptConfig.SPEC.save();
            ModernOptSettings.refresh();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.literal("[ModernOPT] upscaler: " + next.label()), true);
            }
        }
    }

    /** Releases the GL targets and programs while the context is still current. */
    @SubscribeEvent
    public static void onGameShuttingDown(net.neoforged.neoforge.event.GameShuttingDownEvent event) {
        RenderPipeline.shutdown();
        GpuProfiler.shutdown();
        FpsLogger.shutdown();
    }
}
