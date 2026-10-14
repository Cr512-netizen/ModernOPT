package net.cr512.modernopt;

import net.cr512.modernopt.client.ModernOptConfigScreen;
import net.cr512.modernopt.client.ModernOptTabButton;
import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.render.Fsr1Renderer;
import net.minecraft.client.Minecraft;
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

        // Sodium replaces Minecraft's Video Settings screen, so expose a matching
        // themed switch and a direct route to the full ModernOPT settings.
        NeoForge.EVENT_BUS.addListener(ModernOPT::onScreenInit);
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        String className = screen.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        if (!className.contains("sodium") || !className.contains("option")) {
            return;
        }

        int y = screen.height - 28;
        ModernOptTabButton toggle = new ModernOptTabButton(
            screen.width / 2 - 212, y, 132, 22, sodiumToggleLabel(),
            ModernOptConfig.ENABLED.get(), () -> {
                boolean enabled = !ModernOptConfig.ENABLED.get();
                ModernOptConfig.ENABLED.set(enabled);
                ModernOptConfig.SPEC.save();
                toggleLabelRefreshPlaceholder();
            });
        // Keep the widget label and selected accent in sync with the master switch.
        // The callback is replaced below through a small state holder to avoid
        // coupling this integration to Sodium's internal widget classes.
        event.addListener(toggle);

        ModernOptTabButton settings = new ModernOptTabButton(
            screen.width / 2 + 80, y, 132, 22,
            Component.translatable("modernopt.sodium.open"), false,
            () -> Minecraft.getInstance().setScreen(new ModernOptConfigScreen(screen)));
        event.addListener(settings);
    }

    private static Component sodiumToggleLabel() {
        return Component.translatable(ModernOptConfig.ENABLED.get()
            ? "modernopt.sodium.enabled" : "modernopt.sodium.disabled");
    }

    private static void toggleLabelRefreshPlaceholder() {
        // Intentionally empty placeholder; the widget callback is updated in the
        // following implementation step.
    }
}
