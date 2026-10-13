package net.cr512.modernopt;

import net.cr512.modernopt.config.ModernOptConfig;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/** Main entry point. Rendering changes are opt-in and fail closed. */
@Mod(ModernOPT.MOD_ID)
public final class ModernOPT {
    public static final String MOD_ID = "modernopt";

    public ModernOPT() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ModernOptConfig.SPEC);
    }
}
