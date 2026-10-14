package net.cr512.modernopt;

import net.cr512.modernopt.config.ModernOptConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(ModernOPT.MOD_ID)
public final class ModernOPT {
    public static final String MOD_ID = "modernopt";
    public ModernOPT(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ModernOptConfig.SPEC);
    }
}
