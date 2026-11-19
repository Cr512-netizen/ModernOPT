// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Cr512-netizen

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
        migrateLegacyUpscalerNames();
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (minecraft, parent) -> new ModernOptConfigScreen(parent));
        modBus.addListener(net.cr512.modernopt.client.ClientModInit::onRegisterKeyMappings);
    }

    /**
     * Renames the upscaler values of configs written before the MSR/MSR2 rename.
     *
     * <p>Those files contain {@code upscaler = "FSR2"} (now {@code MSR}) and
     * {@code upscaler = "MSR"} (now {@code MSR2}); the {@code [msr]} section is now
     * {@code [msr2]}. The migration runs once per file and leaves a marker so a value the player
     * picks later is never rewritten.</p>
     */
    private static void migrateLegacyUpscalerNames() {
        try {
            java.nio.file.Path path = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("modernopt-client.toml");
            if (!java.nio.file.Files.isReadable(path)) {
                return;
            }
            java.util.List<String> lines = java.nio.file.Files.readAllLines(path,
                    java.nio.charset.StandardCharsets.UTF_8);
            boolean migrated = false;
            if (lines.stream().noneMatch(line -> line.contains("modernopt-rename-msr2"))) {
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String trimmed = line.trim();
                    if (trimmed.startsWith("upscaler")) {
                        if (trimmed.contains("\"FSR2\"")) {
                            lines.set(i, line.replace("\"FSR2\"", "\"MSR\""));
                            migrated = true;
                        } else if (trimmed.contains("\"MSR\"")) {
                            lines.set(i, line.replace("\"MSR\"", "\"MSR2\""));
                            migrated = true;
                        }
                    } else if (trimmed.equals("[msr]")) {
                        lines.set(i, line.replace("[msr]", "[msr2]"));
                        migrated = true;
                    }
                }
                lines.add("# modernopt-rename-msr2");
                java.nio.file.Files.write(path, lines, java.nio.charset.StandardCharsets.UTF_8);
            }
            if (migrated) {
                LOG.info("[ModernOPT] migrated the upscaler names in the config file");
            }
        } catch (Throwable throwable) {
            LOG.warn("[ModernOPT] could not migrate the config file", throwable);
        }
    }
}
