package net.cr512.modernopt;

import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.render.Fsr1Renderer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(ModernOPT.MOD_ID)
public final class ModernOPT {
    public static final String MOD_ID = "modernopt";

    public ModernOPT(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ModernOptConfig.SPEC);

        // Keep shader registration wired even while the world render-target mixins
        // are disabled during the pipeline rebuild. This lets resource reload validate
        // the post-processing shaders without changing the active renderer.
        modEventBus.addListener(Fsr1Renderer::registerShaders);
    }
}
