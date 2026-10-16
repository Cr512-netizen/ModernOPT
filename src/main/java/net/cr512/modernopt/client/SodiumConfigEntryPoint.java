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
    private static final ResourceLocation OVERLAY_ENABLED_ID = id("overlay_enabled");
    private static final ResourceLocation OVERLAY_FPS_ID = id("overlay_fps");
    private static final ResourceLocation OVERLAY_CPU_ID = id("overlay_cpu");
    private static final ResourceLocation OVERLAY_FRAME_TIME_ID = id("overlay_frame_time");
    private static final ResourceLocation OVERLAY_MEMORY_ID = id("overlay_memory");
    private static final ResourceLocation OVERLAY_RENDER_INFO_ID = id("overlay_render_info");
    private static final ResourceLocation OVERLAY_ONE_PERCENT_ID = id("overlay_one_percent_low");
    private static final ResourceLocation OVERLAY_P95_ID = id("overlay_p95");
    private static final ResourceLocation OVERLAY_POSITION_ID = id("overlay_position");
    private static final ResourceLocation OVERLAY_OPACITY_ID = id("overlay_opacity");
    private static final ResourceLocation BENCHMARK_RECORDING_ID = id("benchmark_recording");

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
                        .setDefaultValue(false)
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
                        .setRange(25, 100, 1)
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
                        .setRange(25, 100, 1)
                        .setValueFormatter(value -> Component.literal(value + "%"))
                        .setBinding(ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT::set, ModernOptConfig.DYNAMIC_MIN_SCALE_PERCENT::get)
                        .setStorageHandler(storage)
                        .setDefaultValue(50)
                        .setEnabled(true))
                    .addOption(builder.createIntegerOption(MAX_SCALE_ID)
                        .setName(Component.translatable("modernopt.option.maxScale"))
                        .setTooltip(Component.translatable("modernopt.option.maxScale.desc"))
                        .setRange(25, 100, 1)
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
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.translatable("modernopt.sodium.group.performance"))
                    .addOption(builder.createBooleanOption(OVERLAY_ENABLED_ID)
                        .setName(Component.translatable("modernopt.screen.overlay"))
                        .setTooltip(Component.translatable("modernopt.screen.overlay.tip"))
                        .setBinding(ModernOptConfig.OVERLAY_ENABLED::set, ModernOptConfig.OVERLAY_ENABLED::get)
                        .setStorageHandler(storage).setDefaultValue(true).setEnabled(true))
                    .addOption(builder.createBooleanOption(OVERLAY_FPS_ID)
                        .setName(Component.translatable("modernopt.screen.show_fps"))
                        .setTooltip(Component.translatable("modernopt.screen.show_fps.tip"))
                        .setBinding(ModernOptConfig.OVERLAY_SHOW_FPS::set, ModernOptConfig.OVERLAY_SHOW_FPS::get)
                        .setStorageHandler(storage).setDefaultValue(true).setEnabled(true))
                    .addOption(builder.createBooleanOption(OVERLAY_CPU_ID)
                        .setName(Component.translatable("modernopt.screen.show_cpu"))
                        .setTooltip(Component.translatable("modernopt.screen.show_cpu.tip"))
                        .setBinding(ModernOptConfig.OVERLAY_SHOW_CPU::set, ModernOptConfig.OVERLAY_SHOW_CPU::get)
                        .setStorageHandler(storage).setDefaultValue(true).setEnabled(true))
                    .addOption(builder.createBooleanOption(OVERLAY_FRAME_TIME_ID)
                        .setName(Component.translatable("modernopt.screen.show_frame"))
                        .setTooltip(Component.translatable("modernopt.screen.show_frame.tip"))
                        .setBinding(ModernOptConfig.OVERLAY_SHOW_FRAME_TIME::set, ModernOptConfig.OVERLAY_SHOW_FRAME_TIME::get)
                        .setStorageHandler(storage).setDefaultValue(true).setEnabled(true))
                    .addOption(builder.createBooleanOption(OVERLAY_MEMORY_ID)
                        .setName(Component.translatable("modernopt.screen.show_memory"))
                        .setTooltip(Component.translatable("modernopt.screen.show_memory.tip"))
                        .setBinding(ModernOptConfig.OVERLAY_SHOW_MEMORY::set, ModernOptConfig.OVERLAY_SHOW_MEMORY::get)
                        .setStorageHandler(storage).setDefaultValue(true).setEnabled(true))
                    .addOption(builder.createBooleanOption(OVERLAY_RENDER_INFO_ID)
                        .setName(Component.translatable("modernopt.screen.show_render"))
                        .setTooltip(Component.translatable("modernopt.screen.show_render.tip"))
                        .setBinding(ModernOptConfig.OVERLAY_SHOW_RENDER_INFO::set, ModernOptConfig.OVERLAY_SHOW_RENDER_INFO::get)
                        .setStorageHandler(storage).setDefaultValue(true).setEnabled(true))
                    .addOption(builder.createBooleanOption(OVERLAY_ONE_PERCENT_ID)
                        .setName(Component.translatable("modernopt.screen.show_one_percent_low"))
                        .setTooltip(Component.translatable("modernopt.screen.show_one_percent_low.tip"))
                        .setBinding(ModernOptConfig.OVERLAY_SHOW_ONE_PERCENT_LOW::set, ModernOptConfig.OVERLAY_SHOW_ONE_PERCENT_LOW::get)
                        .setStorageHandler(storage).setDefaultValue(true).setEnabled(true))
                    .addOption(builder.createBooleanOption(OVERLAY_P95_ID)
                        .setName(Component.translatable("modernopt.screen.show_p95"))
                        .setTooltip(Component.translatable("modernopt.screen.show_p95.tip"))
                        .setBinding(ModernOptConfig.OVERLAY_SHOW_P95_FRAME_TIME::set, ModernOptConfig.OVERLAY_SHOW_P95_FRAME_TIME::get)
                        .setStorageHandler(storage).setDefaultValue(true).setEnabled(true))
                    .addOption(builder.createIntegerOption(OVERLAY_POSITION_ID)
                        .setName(Component.translatable("modernopt.screen.position"))
                        .setTooltip(Component.translatable("modernopt.screen.position.tip"))
                        .setRange(0, 3, 1)
                        .setValueFormatter(value -> Component.translatable(switch (value) {
                            case 1 -> "modernopt.position.top_right";
                            case 2 -> "modernopt.position.bottom_left";
                            case 3 -> "modernopt.position.bottom_right";
                            default -> "modernopt.position.top_left";
                        }))
                        .setBinding(ModernOptConfig.OVERLAY_POSITION::set, ModernOptConfig.OVERLAY_POSITION::get)
                        .setStorageHandler(storage).setDefaultValue(0).setEnabled(true))
                    .addOption(builder.createIntegerOption(OVERLAY_OPACITY_ID)
                        .setName(Component.translatable("modernopt.screen.opacity"))
                        .setTooltip(Component.translatable("modernopt.screen.opacity.tip"))
                        .setRange(0, 100, 5)
                        .setValueFormatter(value -> Component.literal(value + "%"))
                        .setBinding(ModernOptConfig.OVERLAY_BACKGROUND_OPACITY::set, ModernOptConfig.OVERLAY_BACKGROUND_OPACITY::get)
                        .setStorageHandler(storage).setDefaultValue(56).setEnabled(true))
                    .addOption(builder.createBooleanOption(BENCHMARK_RECORDING_ID)
                        .setName(Component.translatable("modernopt.screen.benchmark_recording"))
                        .setTooltip(Component.translatable("modernopt.screen.benchmark_recording.tip"))
                        .setBinding(value -> {
                            if (value) {
                                ModernOptConfig.ENABLED.set(true);
                                BenchmarkRecorder.start();
                            } else {
                                BenchmarkRecorder.stop();
                            }
                        }, BenchmarkRecorder::isRecording)
                        .setStorageHandler(storage).setDefaultValue(false).setEnabled(true))
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
