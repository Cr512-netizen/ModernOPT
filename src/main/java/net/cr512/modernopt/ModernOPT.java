package net.cr512.modernopt;

import net.cr512.modernopt.client.ModernOptConfigScreen;
import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.render.Fsr1Renderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(ModernOPT.MOD_ID)
public final class ModernOPT {
    public static final String MOD_ID = "modernopt";

    public ModernOPT(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ModernOptConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class,
            (mc, screen) -> new ModernOptConfigScreen(screen));
        modEventBus.addListener(Fsr1Renderer::registerShaders);

        // Sodium replaces Minecraft's Video Settings screen, so expose a direct entry
        // there through NeoForge's screen event without linking against Sodium internals.
        NeoForge.EVENT_BUS.addListener(ModernOPT::onScreenInit);
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        String className = screen.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        if (!className.contains("sodium") || !className.contains("option")) {
            return;
        }

        int x = screen.width / 2 + 108;
        int y = screen.height - 28;
        Button button = Button.builder(Component.translatable("modernopt.sodium.open"),
                ignored -> Minecraft.getInstance().setScreen(new ModernOptConfigScreen(screen)))
            .bounds(x, y, 104, 20)
            .build();
        event.addListener(button);
    }
}
