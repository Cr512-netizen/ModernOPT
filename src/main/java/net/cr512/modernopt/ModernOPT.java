package net.cr512.modernopt;

import com.mojang.logging.LogUtils;
import net.cr512.modernopt.client.ModernOptConfigScreen;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.cr512.modernopt.config.ModernOptConfig;
import org.slf4j.Logger;

/**
 * ModernOPT - render scale, upscalers, anti-aliasing for Minecraft 1.21.1.
 *
 * <p>The mod is client only: every entry point returns early on a dedicated server and the
 * mixins are registered in the {@code client} section of {@code modernopt.mixins.json}.</p>
 */
@Mod(ModernOPT.MOD_ID)
public final class ModernOPT {

    public static final String MOD_ID = "modernopt";
    public static final Logger LOG = LogUtils.getLogger();

    public ModernOPT(IEventBus modBus, ModContainer container) {
        if (!FMLEnvironment.dist.isClient()) {
            LOG.info("[ModernOPT] dedicated server detected, nothing to do");
            return;
        }

        container.registerConfig(ModConfig.Type.CLIENT, ModernOptConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (minecraft, parent) -> new ModernOptConfigScreen(parent));
        modBus.addListener(net.cr512.modernopt.client.ClientModInit::onRegisterKeyMappings);
    }
}
