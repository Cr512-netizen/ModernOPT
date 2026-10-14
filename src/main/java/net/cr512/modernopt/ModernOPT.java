package net.cr512.modernopt;

import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.render.Fsr1Renderer;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.bus.api.IEventBus;

@Mod(ModernOPT.MOD_ID)
public final class ModernOPT {
    public static final String MOD_ID = "modernopt";
    public ModernOPT(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ModernOptConfig.SPEC);
        modEventBus.addListener(Fsr1Renderer::registerShaders);
    }
}
