package net.cr512.modernopt.compat.sodium;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.EnumOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ExternalButtonOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.cr512.modernopt.ModernOPT;
import net.cr512.modernopt.client.ModernOptConfigScreen;
import net.cr512.modernopt.config.ModernOptConfig;
import net.cr512.modernopt.config.ModernOptSettings;
import net.cr512.modernopt.config.MsaaLevel;
import net.cr512.modernopt.config.UpscalerType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

/**
 * Sodium 0.8.13 config entry point.
 *
 * <p>Sodium discovers this class through the {@code sodium:config_api_user} mod property in
 * {@code neoforge.mods.toml}, exactly like Iris does. It adds a ModernOPT entry to Sodium's
 * video settings: a page with the full settings screen and a native page with the most
 * common options. Sodium is an optional compile-time dependency; this class is never
 * referenced by ModernOPT itself, so it is simply not loaded when Sodium is absent.</p>
 */
public final class ModernOptSodiumConfig implements ConfigEntryPoint {

    private static final ResourceLocation ENABLED = id("enabled");
    private static final ResourceLocation RENDER_SCALE = id("render_scale");
    private static final ResourceLocation UPSCALER = id("upscaler");
    private static final ResourceLocation SOFTNESS = id("softness");
    private static final ResourceLocation FSR_EASU_SHARPNESS = id("fsr_easu_sharpness");
    private static final ResourceLocation FSR_RCAS_SHARPNESS = id("fsr_rcas_sharpness");
    private static final ResourceLocation MSAA = id("msaa");
    private static final ResourceLocation SSAA = id("ssaa");
    private static final ResourceLocation FXAA = id("fxaa");
    private static final ResourceLocation OVERLAY = id("overlay");
    private static final ResourceLocation OPEN_SETTINGS = id("open_settings");

    /** Sodium runs this once after an option was changed and the screen is applied. */
    private static final StorageEventHandler SAVE = ModernOptSodiumConfig::save;

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        ModOptionsBuilder mod = builder.registerOwnModOptions()
                .setName("ModernOPT")
                .setVersion(version())
                .setIcon(id("textures/gui/config-icon.png"))
                .setColorTheme(builder.createColorTheme().setBaseThemeRGB(0x2E9E8F));

        // Same layout as Iris: one external page that opens the mod's own screen...
        mod.addPage(builder.createExternalPage()
                .setName(Component.translatable("modernopt.config.title"))
                .setScreenConsumer(ModernOptSodiumConfig::openScreen));

        // ...plus a native page so the common options can be changed without leaving Sodium.
        OptionPageBuilder page = builder.createOptionPage()
                .setName(Component.translatable("modernopt.sodium.page"));

        OptionGroupBuilder general = builder.createOptionGroup()
                .setName(Component.translatable("modernopt.sodium.group.general"))
                .addOption(booleanOption(builder, ENABLED, "modernopt.option.enabled",
                        ModernOptConfig.ENABLED.getDefault(),
                        value -> ModernOptConfig.ENABLED.set(value),
                        () -> ModernOptConfig.ENABLED.get()))
                .addOption(booleanOption(builder, OVERLAY, "modernopt.option.overlay",
                        ModernOptConfig.SHOW_OVERLAY.getDefault(),
                        value -> ModernOptConfig.SHOW_OVERLAY.set(value),
                        () -> ModernOptConfig.SHOW_OVERLAY.get()));
        page.addOptionGroup(general);

        OptionGroupBuilder scaling = builder.createOptionGroup()
                .setName(Component.translatable("modernopt.sodium.group.scaling"))
                .addOption(integerOption(builder, RENDER_SCALE, "modernopt.option.renderScale",
                        ModernOptConfig.RENDER_SCALE_PERCENT.getDefault(), 25, 100,
                        value -> ModernOptConfig.RENDER_SCALE_PERCENT.set(value),
                        () -> ModernOptConfig.RENDER_SCALE_PERCENT.get()))
                .addOption(enumOption(builder, UPSCALER, "modernopt.option.upscaler", UpscalerType.class,
                        ModernOptConfig.UPSCALER.getDefault(),
                        value -> ModernOptConfig.UPSCALER.set(value),
                        () -> ModernOptConfig.UPSCALER.get()))
                .addOption(integerOption(builder, SOFTNESS, "modernopt.option.softness",
                        ModernOptConfig.SOFTNESS.getDefault(), 0, 100,
                        value -> ModernOptConfig.SOFTNESS.set(value),
                        () -> ModernOptConfig.SOFTNESS.get()))
                .addOption(integerOption(builder, FSR_EASU_SHARPNESS, "modernopt.option.fsrEasuSharpness",
                        ModernOptConfig.FSR_EASU_SHARPNESS.getDefault(), 0, 100,
                        value -> ModernOptConfig.FSR_EASU_SHARPNESS.set(value),
                        () -> ModernOptConfig.FSR_EASU_SHARPNESS.get()))
                .addOption(integerOption(builder, FSR_RCAS_SHARPNESS, "modernopt.option.fsrRcasSharpness",
                        ModernOptConfig.FSR_RCAS_SHARPNESS.getDefault(), 0, 100,
                        value -> ModernOptConfig.FSR_RCAS_SHARPNESS.set(value),
                        () -> ModernOptConfig.FSR_RCAS_SHARPNESS.get()));
        page.addOptionGroup(scaling);

        OptionGroupBuilder antialiasing = builder.createOptionGroup()
                .setName(Component.translatable("modernopt.sodium.group.antialiasing"))
                .addOption(enumOption(builder, MSAA, "modernopt.option.msaa", MsaaLevel.class,
                        ModernOptConfig.MSAA.getDefault(),
                        value -> ModernOptConfig.MSAA.set(value),
                        () -> ModernOptConfig.MSAA.get()))
                .addOption(booleanOption(builder, SSAA, "modernopt.option.ssaa",
                        ModernOptConfig.SSAA.getDefault(),
                        value -> ModernOptConfig.SSAA.set(value),
                        () -> ModernOptConfig.SSAA.get()))
                .addOption(booleanOption(builder, FXAA, "modernopt.option.fxaa",
                        ModernOptConfig.FXAA.getDefault(),
                        value -> ModernOptConfig.FXAA.set(value),
                        () -> ModernOptConfig.FXAA.get()));
        page.addOptionGroup(antialiasing);

        // Iris puts its shader pack button in an unnamed group at the end of the page.
        ExternalButtonOptionBuilder openButton = builder.createExternalButtonOption(OPEN_SETTINGS)
                .setName(Component.translatable("modernopt.sodium.open"))
                .setTooltip(Component.translatable("modernopt.sodium.open.tooltip"))
                .setScreenConsumer(ModernOptSodiumConfig::openScreen);
        page.addOptionGroup(builder.createOptionGroup().addOption(openButton));

        mod.addPage(page);
    }

    private static BooleanOptionBuilder booleanOption(ConfigBuilder builder, ResourceLocation id, String nameKey,
            boolean defaultValue, java.util.function.Consumer<Boolean> setter,
            java.util.function.Supplier<Boolean> getter) {
        return builder.createBooleanOption(id)
                .setName(Component.translatable(nameKey))
                .setTooltip(Component.translatable(nameKey + ".tooltip"))
                .setDefaultValue(defaultValue)
                .setImpact(OptionImpact.LOW)
                .setBinding(setter, getter)
                .setStorageHandler(SAVE);
    }

    private static IntegerOptionBuilder integerOption(ConfigBuilder builder, ResourceLocation id, String nameKey,
            int defaultValue, int min, int max, java.util.function.Consumer<Integer> setter,
            java.util.function.Supplier<Integer> getter) {
        return builder.createIntegerOption(id)
                .setName(Component.translatable(nameKey))
                .setTooltip(Component.translatable(nameKey + ".tooltip"))
                .setDefaultValue(defaultValue)
                .setRange(min, max, 1)
                .setValueFormatter(value -> Component.literal(value + "%"))
                .setImpact(OptionImpact.MEDIUM)
                .setBinding(setter, getter)
                .setStorageHandler(SAVE);
    }

    private static <E extends Enum<E>> EnumOptionBuilder<E> enumOption(ConfigBuilder builder, ResourceLocation id,
            String nameKey, Class<E> type, E defaultValue, java.util.function.Consumer<E> setter,
            java.util.function.Supplier<E> getter) {
        return builder.createEnumOption(id, type)
                .setName(Component.translatable(nameKey))
                .setTooltip(Component.translatable(nameKey + ".tooltip"))
                .setDefaultValue(defaultValue)
                .setElementNameProvider(value -> Component.literal(label(value)))
                .setImpact(OptionImpact.HIGH)
                .setBinding(setter, getter)
                .setStorageHandler(SAVE);
    }

    private static <E extends Enum<E>> String label(E value) {
        if (value instanceof MsaaLevel level) {
            return level.label();
        }
        if (value instanceof UpscalerType type) {
            return type.label();
        }
        return value.name();
    }

    private static void openScreen(Screen parent) {
        Minecraft.getInstance().setScreen(new ModernOptConfigScreen(parent));
    }

    private static void save() {
        ModernOptConfig.SPEC.save();
        ModernOptSettings.refresh();
    }

    private static String version() {
        return ModList.get().getModContainerById(ModernOPT.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("dev");
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ModernOPT.MOD_ID, path);
    }
}
