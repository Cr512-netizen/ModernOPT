package net.cr512.modernopt;

import net.cr512.modernopt.config.ModernOptConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(ModernOPT.MOD_ID)
public final class ModernOPT {
    public static final String MOD_ID = "modernopt";

    public ModernOPT(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ModernOptConfig.SPEC);
        // Rendering mixins are deliberately not enabled while the render-target pipeline
        // is being rebuilt. This keeps water, translucency, and the main framebuffer intact.
    }
}
