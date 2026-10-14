package net.cr512.modernopt.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Compact, theme-matched navigation/control pill used by the ModernOPT screens
 * and the Sodium settings entry point.
 */
public final class ModernOptTabButton extends AbstractWidget {
    private static final int ACCENT = 0xFF69D6C5;
    private static final int TEXT = 0xFFF1F5FA;
    private static final int MUTED = 0xFFB6C1D0;
    private static final int IDLE = 0xFF202735;
    private static final int HOVER = 0xFF2B3747;
    private static final int ACTIVE = 0xFF263F45;
    private static final int OUTLINE = 0xFF3A4657;

    private final Runnable action;
    private boolean selected;

    public ModernOptTabButton(int x, int y, int width, int height, Component label,
                              boolean selected, Runnable action) {
        super(x, y, width, height, label);
        this.selected = selected;
        this.action = action;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean hovered = isHoveredOrFocused();
        int background = selected ? ACTIVE : hovered ? HOVER : IDLE;
        graphics.fill(getX(), getY(), getX() + width, getY() + height, background);
        graphics.fill(getX(), getY(), getX() + width, getY() + 1, selected ? ACCENT : OUTLINE);
        graphics.fill(getX(), getY() + height - 1, getX() + width, getY() + height, OUTLINE);
        graphics.fill(getX(), getY(), getX() + 1, getY() + height, OUTLINE);
        graphics.fill(getX() + width - 1, getY(), getX() + width, getY() + height, OUTLINE);
        if (selected) {
            graphics.fill(getX() + 7, getY() + height - 3, getX() + width - 7, getY() + height - 1, ACCENT);
        }
        int color = !active ? 0xFF6F7784 : selected ? TEXT : hovered ? TEXT : MUTED;
        graphics.drawCenteredString(Minecraft.getInstance().font, getMessage(),
            getX() + width / 2, getY() + (height - 8) / 2, color);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (active) {
            action.run();
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
