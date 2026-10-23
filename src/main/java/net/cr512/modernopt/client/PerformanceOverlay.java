package net.cr512.modernopt.client;

import net.cr512.modernopt.config.ModernOptSettings;
import net.cr512.modernopt.render.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** One-line status readout drawn on top of the vanilla HUD. */
public final class PerformanceOverlay {

    private PerformanceOverlay() {
    }

    public static void render(GuiGraphics graphics) {
        ModernOptSettings.refresh();
        if (!ModernOptSettings.overlay()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.font == null) {
            return;
        }

        int internalWidth = RenderPipeline.internalWidth();
        int internalHeight = RenderPipeline.internalHeight();
        int windowWidth = minecraft.getWindow().getWidth();
        int windowHeight = minecraft.getWindow().getHeight();
        int samples = RenderPipeline.activeSamples();

        StringBuilder builder = new StringBuilder(96);
        builder.append("ModernOPT ")
                .append(minecraft.getFps()).append(" fps | ")
                .append(ModernOptSettings.upscaler().label()).append(" | ");
        if (internalWidth > 0) {
            builder.append(internalWidth).append('x').append(internalHeight);
        } else {
            builder.append("native");
        }
        builder.append(" -> ").append(windowWidth).append('x').append(windowHeight);
        builder.append(" | MSAA ").append(samples > 1 ? samples + "x" : "off");
        if (ModernOptSettings.ssaa()) {
            builder.append(" | SSAA");
        }
        if (ModernOptSettings.fxaa()) {
            builder.append(" | FXAA");
        }

        graphics.drawString(minecraft.font, builder.toString(), 4, 4, 0xFFFFFFFF, true);
    }
}
