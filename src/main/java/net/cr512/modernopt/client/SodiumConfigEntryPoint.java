package net.cr512.modernopt.client;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.cr512.modernopt.config.ModernOptConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Registers a native Sodium options page. No custom Screen or buttons are injected
 * into Sodium; Sodium owns layout, navigation, and applying the values.
 */
@ConfigEntryPointForge("modernopt")
public final class SodiumConfigEntryPoint implements ConfigEntryPoint {
    private static final ResourceLocation ENABLED_ID = id("enabled");
    private static final ResourceLocation SCALE_ID = id("render_scale");
    private static final ResourceLocation PRESET_ID = id("upscaling_preset");
    private static final ResourceLocation UPSCALER_ID = id("upscaler");
    private static final ResourceLocation FXAA_ID = id("fxaa");
    private static final ResourceLocation SSAA_ID = id("ssaa");
    private static final ResourceLocation MSAA_ID = id("msaa");
    private static final ResourceLocation DYNAMIC_ID = id("dynamic_resolution");
    private static final ResourceLocation TARGET_FPS_ID = id("target_fps");
    private static final ResourceLocation MIN_SCALE_ID = id("minimum_scale");
    private static final ResourceLocation MAX_SCALE_ID = id("maximum_scale");
    private static final ResourceLocation IRIS_SHADOW_SCALE_ENABLED_ID = id("iris_shadow_scale_enabled");
    private static final ResourceLocation IRIS_SHADOW_RESOLUTION_ID = id("iris_shadow_resolution");
    private static final ResourceLocation SHADOW_FRAME_SKIP_ENABLED_ID = id("shadow_frame_skip_enabled");
    private static final ResourceLocation SHADOW_UPDATE_FPS_ID = id("shadow_update_fps");

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        StorageEventHandler storage = this::save;
        builder.registerOwnModOptions()
            .setName("ModernOPT")
            .addPage(builder.createOptionPage()
                .setName(Component.translatable("modernopt.sodium.page.rendering"))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.translatable("modernopt.sodium.group.safety"))
                    .addOption(builder.createBooleanOption(ENABLED_ID)
                        .setName(Component.translatable("modernopt.option.enabled"))
                        .setTooltip(Component.translatable("modernopt.option.enabled.desc"))
                        .setBinding(ModernOptConfig.ENABLED::set, ModernOptConfig.ENABLED::get)
                        .setStorageHandler(storage)
                        .setDefaultValue(true)
                        .setEnabled(true))
                )
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.translatable("modernopt.sodium.group.upscaling"))
                    .addOption(builder.createEnumOption(PRESET_ID, UpscalingPreset.class)
                        .setName(Component.translatable("modernopt.profile.title"))
                        .setTooltip(Component.translatable("modernopt.profile.desc"))
                        .setElementNameProvider(value -> Component.translatable(switch (value) {
                            case QUALITY -> "modernopt.profile.quality";
                            case BALANCED -> "modernopt.profile.balanced";
                            case PERFORMANCE -> "modernopt.profile.performance";
                            case ULTRA_PERFORMANCE -> "modernopt.profile.ultra_performance";
                            case CUSTOM -> "modernopt.profile.custom";
                        }))
                        .setBinding(SodiumConfigEntryPoint::applyPreset, SodiumConfigEntryPoint::currentPreset)
                        .setStorageHandler(storage)
                        .setDefaultValue(UpscalingPreset.BALANCED)
                        .setEnabled(true))
                    .addOption(builder.createIntegerOption(SCALE_ID)
                        .setName(Component.translatable("modernopt.option.scale"))
                        .setTooltip(Component.translatable("modernopt.option.scale.desc"))
                        .setRange(25, 100, 5)
                        .setValueFormatter(value -> Component.literal(value + "%"))
                        .setBinding(ModernOptConfig.RENDER_SCALE_PERCENT::set, ModernOptConfig.RENDER_SCALE_PERCENT::get)
                        .setStorageHandler(storage)
                        .setDefaultValue(100)
                        .setEnabled(true))
                    .addOption(builder.createEnumOption(UPSCALER_ID, UpscalerMode.class)
                        .setName(Component.translatable("modernopt.option.upscaler"))
                        .setTooltip(Component.translatable("modernopt.option.upscaler.desc"))
                        .setElementNameProvider(value -> Component.translatable(switch (value) {
                            case NONE -> "modernopt.upscaler.none";
                            case FSR1 -> "modernopt.upscaler.fsr1";
                            case BILINEAR -> "modernopt.upscaler.bilinear";
                            case NEAREST_NEIGHBOR -> "modernopt.upscaler.nearest";
                            case SOFT_SCALE -> "modernopt.upscaler.softscale";
                        }))
                        .setBinding(value -> ModernOptConfig.UPSCALER_MODE.set(value.ordinal()),
                            () -> UpscalerMode.values()[Math.max(0, Math.min(UpscalerMode.values().length - 1, ModernOptConfig.UPSCALER_MODE.get()))])
                        .setStorageHandler(storage)
                        .setDefaultValue(UpscalerMode.BILINEAR)
                        .setEnabled(true))
                    .addOption(builder.createBooleanOption(DYNAMIC_ID)
                        .setName(Component.translatable("modernopt.option.dynamic"))
                        .setTooltip(Component.translatable("modernopt.option.dynamic.desc"))
                        .setBinding(ModernOptConfig.DYNAMIC_RESOLUTION_ENABLED::set, ModernOptConfig.DYNAMIC_RESOLUTION_ENABLED::get)
                        .setStorageHandler(storage)
                        .setDefaultValue(false)
                        .setEnabled(true))
                    .addOption(builder.createIntegerOption(TARGET_FPS_ID)
                        .setName(Component.translatable("modernopt.option.targetfps"))
                        .setTooltip(Component.translatable("modernopt.option.targetfps.desc"))
                        .setRange(30, 540, 10)
                        .setValueFormatter(value -> Component.literal(value + " FPS"))
                        .setBinding(ModernOptConfig.DYNAMIC_TARGET_FPS::set, ModernOptConfig.DYNAMIC_TARGET_FPS::get)
                        .setStorageHandler(storage)
                        .setDefaultValue(120)
                        .setEnabled(true))
                    .addOption(builder.createIntegerOption(MIN_SCALE_ID)
                        .setName(Component.translatable("modernopt.option.minScale"))
                        .setTooltip(Component.translatable("modernopt.option.minScale.desc"))
                        .setRange(25, 100, 5)
                        .setValueFormatter(value -> Component.literal(value + "%"))
                        .setBinding(ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT::set, ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT::get)
                        .setStorageHandler(storage)
                        .setDefaultValue(50)
                        .setEnabled(true))
                    .addOption(builder.createIntegerOption(MAX_SCALE_ID)
                        .setName(Component.translatable("modernopt.option.maxScale"))
                        .setTooltip(Component.translatable("modernopt.option.maxScale.desc"))
                        .setRange(25, 100, 5)
                        .setValueFormatter(value -> Component.literal(value + "%"))
                        .setBinding(ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT::set, ModernOptConfig.DYNAMIC_MAX_SCALE_PERCENT::get)
                        .setStorageHandler(storage)
                        .setDefaultValue(100)
                        .setEnabled(true))
                )
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.translatable("modernopt.sodium.group.iris"))
                    .addOption(builder.createBooleanOption(IRIS_SHADOW_SCALE_ENABLED_ID)
                        .setName(Component.translatable("modernopt.option.irisShadowScale"))
                        .setTooltip(Component.translatable("modernopt.option.irisShadowScale.desc"))
                        .setBinding(ModernOptConfig.IRIS_SHADOW_SCALE_ENABLED::set, ModernOptConfig.IRIS_SHADOW_SCALE_ENABLED::get)
                        .setStorageHandler(storage)
                        .setDefaultValue(false)
                        .setEnabled(true))
                    .addOption(builder.createIntegerOption(IRIS_SHADOW_RESOLUTION_ID)
                        .setName(Component.translatable("modernopt.option.irisShadowResolution"))
                        .setTooltip(Component.translatable("modernopt.option.irisShadowResolution.desc"))
                        .setRange(512, 8192, 256)
                        .setValueFormatter(value -> Component.literal(value + " px"))
                        .setBinding(ModernOptConfig.IRIS_SHADOW_RESOLUTION::set, ModernOptConfig.IRIS_SHADOW_RESOLUTION::get)
                        .setStorageHandler(storage)
                        .setDefaultValue(1024)
                        .setEnabled(true))
                    .addOption(builder.createBooleanOption(SHADOW_FRAME_SKIP_ENABLED_ID)
                        .setName(Component.translatable("modernopt.option.shadowFrameSkip"))
                        .setTooltip(Component.translatable("modernopt.option.shadowFrameSkip.desc"))
                        .setBinding(ModernOptConfig.SHADOW_FRAME_SKIP_ENABLED::set, ModernOptConfig.SHADOW_FRAME_SKIP_ENABLED::get)
                        .setStorageHandler(storage)
                        .setDefaultValue(false)
                        .setEnabled(true))
                    .addOption(builder.createIntegerOption(SHADOW_UPDATE_FPS_ID)
                        .setName(Component.translatable("modernopt.option.shadowUpdateFps"))
                        .setTooltip(Component.translatable("modernopt.option.shadowUpdateFps.desc"))
                        .setRange(40, 240, 10)
                        .setValueFormatter(value -> Component.literal(value + " FPS"))
                        .setBinding(ModernOptConfig.SHADOW_UPDATE_FPS::set, ModernOptConfig.SHADOW_UPDATE_FPS::get)
                        .setStorageHandler(storage)
                        .setDefaultValue(60)
                        .setEnabled(true))
                )
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.translatable("modernopt.sodium.group.anti_aliasing"))
                    .addOption(builder.createBooleanOption(FXAA_ID)
                        .setName(Component.translatable("modernopt.option.fxaa"))
                        .setTooltip(Component.translatable("modernopt.option.fxaa.desc"))
                        .setBinding(ModernOptConfig.FXAA_ENABLED::set, ModernOptConfig.FXAA_ENABLED::get)
                        .setStorageHandler(storage)
                        .setDefaultValue(false)
                        .setEnabled(true))
                    .addOption(builder.createBooleanOption(SSAA_ID)
                        .setName(Component.translatable("modernopt.option.ssaa"))
                        .setTooltip(Component.translatable("modernopt.option.ssaa.desc"))
                        .setBinding(ModernOptConfig.SSAA_ENABLED::set, ModernOptConfig.SSAA_ENABLED::get)
                        .setStorageHandler(storage)
                        .setDefaultValue(false)
                        .setEnabled(true))
                    .addOption(builder.createIntegerOption(MSAA_ID)
                        .setName(Component.translatable("modernopt.option.msaa"))
                        .setTooltip(Component.translatable("modernopt.option.msaa.desc"))
                        // Use a five-step slider so unsupported values such as 6x or 10x
                        // cannot be selected. The config continues to store real sample counts.
                        .setRange(0, 4, 1)
                        .setValueFormatter(value -> Component.literal(switch (value) {
                            case 1 -> "2x";
                            case 2 -> "4x";
                            case 3 -> "8x";
                            case 4 -> "16x";
                            default -> "Off";
                        }))
                        .setBinding(value -> ModernOptConfig.MSAA_SAMPLES.set(switch (value) {
                            case 1 -> 2;
                            case 2 -> 4;
                            case 3 -> 8;
                            case 4 -> 16;
                            default -> 0;
                        }), () -> switch (ModernOptConfig.MSAA_SAMPLES.get()) {
                            case 2 -> 1;
                            case 4 -> 2;
                            case 8 -> 3;
                            case 16 -> 4;
                            default -> 0;
                        })
                        .setStorageHandler(storage)
                        .setDefaultValue(0)
                        .setEnabled(true))
                )
            );
    }

    private void save() {
        ModernOptConfig.SPEC.save();
    }

    private static void applyPreset(UpscalingPreset preset) {
        switch (preset) {
            case QUALITY -> {
                ModernOptConfig.RENDER_SCALE_PERCENT.set(75);
                ModernOptConfig.UPSCALER_MODE.set(1); // FSR1
            }
            case BALANCED -> {
                ModernOptConfig.RENDER_SCALE_PERCENT.set(60);
                ModernOptConfig.UPSCALER_MODE.set(1); // FSR1
            }
            case PERFORMANCE -> {
                ModernOptConfig.RENDER_SCALE_PERCENT.set(50);
                ModernOptConfig.UPSCALER_MODE.set(2); // Bilinear
            }
            case ULTRA_PERFORMANCE -> {
                ModernOptConfig.RENDER_SCALE_PERCENT.set(35);
                ModernOptConfig.UPSCALER_MODE.set(0); // Direct blit, no post-process shader
            }
            case CUSTOM -> { }
        }
    }

    private static UpscalingPreset currentPreset() {
        int scale = ModernOptConfig.RENDER_SCALE_PERCENT.get();
        int mode = ModernOptConfig.UPSCALER_MODE.get();
        if (scale == 75 && mode == 1) return UpscalingPreset.QUALITY;
        if (scale == 60 && mode == 1) return UpscalingPreset.BALANCED;
        if (scale == 50 && mode == 2) return UpscalingPreset.PERFORMANCE;
        if (scale == 35 && mode == 0) return UpscalingPreset.ULTRA_PERFORMANCE;
        return UpscalingPreset.CUSTOM;
    }

    private enum UpscalingPreset {
        QUALITY,
        BALANCED,
        PERFORMANCE,
        ULTRA_PERFORMANCE,
        CUSTOM
    }

    private enum UpscalerMode {
        NONE,
        FSR1,
        BILINEAR,
        NEAREST_NEIGHBOR,
        SOFT_SCALE
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("modernopt", path);
    }
}
