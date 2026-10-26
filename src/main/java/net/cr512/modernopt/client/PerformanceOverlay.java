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
        if (!ModernOptSettings.enabled() || !ModernOptSettings.overlay()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.font == null) {
            return;
        }
        // The F3 debug screen owns the top-left corner and is not shadowed. Do not cover its
        // first line with the status line; F3 already reports the frame rate.
        if (minecraft.getDebugOverlay().showDebugScreen()) {
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
                .append(RenderPipeline.activeUpscalerLabel()).append(" | ");
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

        // The caller runs after the HUD has been submitted, so a flush here is cheap and makes
        // the line independent of whatever the HUD left in the shared buffer source.
        String text = builder.toString();
        graphics.flush();
        graphics.fill(2, 2, 6 + minecraft.font.width(text), 16, 0x8F000000);
        graphics.drawString(minecraft.font, text, 4, 4, 0xFFFFFFFF, true);
        graphics.flush();
    }
}
