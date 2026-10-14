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

        // Sodium replaces Minecraft's Video Settings screen, so expose both the master
        // switch and a direct settings entry without linking against Sodium internals.
        NeoForge.EVENT_BUS.addListener(ModernOPT::onScreenInit);
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        String className = screen.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        if (!className.contains("sodium") || !className.contains("option")) {
            return;
        }

        int y = screen.height - 28;
        Button toggle = Button.builder(sodiumToggleLabel(), button -> {
                ModernOptConfig.ENABLED.set(!ModernOptConfig.ENABLED.get());
                ModernOptConfig.SPEC.save();
                button.setMessage(sodiumToggleLabel());
            })
            .bounds(screen.width / 2 - 212, y, 112, 20)
            .build();
        event.addListener(toggle);

        Button settings = Button.builder(Component.translatable("modernopt.sodium.open"),
                ignored -> Minecraft.getInstance().setScreen(new ModernOptConfigScreen(screen)))
            .bounds(screen.width / 2 + 108, y, 104, 20)
            .build();
        event.addListener(settings);
    }

    private static Component sodiumToggleLabel() {
        return Component.translatable(ModernOptConfig.ENABLED.get()
            ? "modernopt.sodium.enabled" : "modernopt.sodium.disabled");
    }
}
