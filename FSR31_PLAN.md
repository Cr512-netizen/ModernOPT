# FSR 3.1 Native AA Implementation Plan for ModernOPT

**Status: FEASIBLE** — Pure upscaler, no frame generation, builds on existing FSR 2 pipeline.

**Target**: Better quality than FSR 2, same GPU requirements (compute shaders optional), zero engine changes.

---

## What is FSR 3.1 Native AA?

From AMD FidelityFX SDK 1.0.4+:
- **Improved EASU** (Edge-Adaptive Spatial Upsampling) — better edge reconstruction
- **Improved RCAS** (Robust Contrast-Adaptive Sharpening) — fewer ringing artifacts  
- **Optional Denoiser** — for noisy inputs (ray tracing, low sample counts)
- **Optional Reactivity Mask** — temporal stability without full temporal pass
- **Compute + Fragment paths** — runs on any GL 4.3+ / Vulkan 1.1+ GPU
- **NO Frame Generation** — pure upscaler, drop-in FSR 2 replacement

---

## ModernOPT Assets (Reusable)

| Component | FSR 3.1 Use |
|-----------|-------------|
| `RenderPipeline.reconstructFsr2` / `reconstructSpatial` | Insert FSR 3.1 path here |
| `ShaderLibrary` + `draw`/`drawTemporal` | Dispatch compute or fragment shaders |
| `ScaledRenderTarget` + `HalfFloatTarget` | History buffers (if using reactivity) |
| `UpscalerType` enum + config | Add `FSR31` variant |
| `ModernOptSettings` cache | Add `fsr31Sharpness`, `fsr31Denoise`, `fsr31Reactivity` |
| Iris/Sodium compat | Same as FSR 2 |

---

## Phase 1: Shader Port (Week 1-2)

### 1.1 Source: FidelityFX SDK `ffx_fsr31_upscale`

Key files to port:
```
ffx_fsr31.h                    → GLSL common functions
ffx_fsr31_upscale.hlsl         → fg_easu.comp / fg_easu.fsh
ffx_fsr31_rcas.hlsl            → fg_rcas.comp / fg_rcas.fsh
ffx_fsr31_denoiser.hlsl        → fg_denoise.comp / fg_denoise.fsh (optional)
ffx_fsr31_reactivity.hlsl      → fg_reactivity.comp / fg_reactivity.fsh (optional)
```

### 1.2 Shader Variants

| Shader | Path | Input | Output |
|--------|------|-------|--------|
| **EASU** | Compute (preferred) + Fragment fallback | Color + Depth (optional) | Upscaled color |
| **RCAS** | Compute + Fragment | EASU output | Sharpened |
| **Denoiser** | Compute + Fragment | Color + Normal/Depth (optional) | Denoised |
| **Reactivity** | Compute + Fragment | Current + Previous + Depth | Reactivity mask |

### 1.3 GLSL Adaptation Checklist
- [ ] HLSL → GLSL syntax (`Texture2D` → `sampler2D`, `RWTexture2D` → `image2D`)
- [ ] Wave intrinsics → subgroup ops (GLSL `#extension GL_KHR_shader_subgroup_basic: require`)
- [ ] Constant buffers → `uniform` / `buffer` blocks
- [ ] Resource binding → `layout(binding = N)` / `binding` qualifiers
- [ ] `#version 450 core` for compute, `#version 150 core` for fragment fallback
- [ ] Embed in Java `Shaders.java` style (text blocks) — no resource pipeline deps

---

## Phase 2: Pipeline Integration (Week 2)

### 2.1 New `UpscalerType.FSR31`

```java
// ModernOptConfig.java
public enum UpscalerType {
    NEAREST, BILINEAR, SOFT, BOX, FSR1, FSR2, FSR31
}

// ModernOptSettings.java
private static volatile int fsr31Sharpness = 30;
private static volatile int fsr31Denoise = 0;      // 0=off, 1=on
private static volatile int fsr31Reactivity = 0;   // 0=off, 1=on
```

### 2.2 RenderPipeline: New Reconstruction Path

```java
// In reconstructSpatial():
boolean fsr31Path = !cachedSsaa && upscaler == UpscalerType.FSR31;

if (fsr31Path) {
    reconstructFsr31(main);
} else if (fsr1Path) {
    // existing FSR 1/2 path
}

// New method:
private static void reconstructFsr31(RenderTarget main) {
    // 1. (Optional) Denoise input
    // 2. EASU upscale to output resolution
    // 3. (Optional) Reactivity mask from history
    // 4. RCAS sharpen
    // 5. (Optional) FXAA
}
```

### 2.3 Compute vs Fragment Dispatch

```java
// In FGRenderer-style dispatch:
if (GL.getCapabilities().OpenGL43 && cachedFsr31Compute) {
    dispatchCompute(easuProgram, width/8, height/8, 1);
    memoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
    dispatchCompute(rcasProgram, width/8, height/8, 1);
} else {
    // Fragment fallback via ShaderLibrary.draw()
    drawFragment(easuShader, ...);
    drawFragment(rcasShader, ...);
}
```

---

## Phase 3: Config & UI (Week 2-3)

### 3.1 ModernOptConfig Additions
```java
FSR31_SHARPNESS = defineInRange("fsr31Sharpness", 30, 0, 100);
FSR31_DENOISE = define("fsr31Denoise", false);
FSR31_REACTIVITY = define("fsr31Reactivity", false);
FSR31_COMPUTE = define("fsr31Compute", true);  // prefer compute path
```

### 3.2 ModernOptConfigScreen
```java
// Two new sliders + two toggles in "Scaling" group:
// - FSR 3.1 Sharpness (0-100)
// - Denoiser (on/off) 
// - Reactivity Mask (on/off) — needs history buffer
// - Prefer Compute Shaders (on/off)
```

### 3.3 Lang Files (en_us.json / ru_ru.json)
```json
"modernopt.option.fsr31Sharpness": "FSR 3.1 Sharpness",
"modernopt.option.fsr31Denoise": "FSR 3.1 Denoiser",
"modernopt.option.fsr31Reactivity": "FSR 3.1 Reactivity Mask",
"modernopt.option.fsr31Compute": "Prefer Compute Shaders"
```

---

## Phase 4: History Buffer (Optional, for Reactivity)

### 4.1 If Reactivity Enabled
```java
// Reuse existing HalfFloatTarget history ping-pong
// Reactivity shader: current frame + history → reactivity mask
// Mask feeds into RCAS to reduce sharpening on moving objects
```

### 4.2 Memory Impact
| Feature | Extra VRAM |
|---------|------------|
| Base FSR 3.1 | 0 (uses existing scaled target) |
| + Reactivity | +1 HalfFloatTarget (RGBA16F @ output res) |
| + Denoiser (temp) | +1 RGBA16F temp target |

---

## Phase 5: Testing & Polish (Week 3-4)

### 5.1 Quality Benchmarks
| Scene | FSR 2 | FSR 3.1 | Target |
|-------|-------|---------|--------|
| Static geometry | baseline | +5-10% SSIM | ✅ |
| Thin geometry (leaves, fences) | ghosting | reduced | ✅ |
| High frequency (text, UI) | ringing | less ringing | ✅ |
| Noisy input (RT, low spp) | artifacts | denoiser helps | ✅ |

### 5.2 Performance Targets (RTX 3060 / RX 6600 XT @ 1080p)
| Path | EASU | RCAS | Total |
|------|------|------|-------|
| Compute | ~0.8ms | ~0.3ms | ~1.1ms |
| Fragment | ~1.5ms | ~0.5ms | ~2.0ms |
| + Denoiser | +0.5ms | — | +0.5ms |
| + Reactivity | +0.3ms | — | +0.3ms |

### 5.3 Compatibility Matrix
| Feature | Works? | Notes |
|---------|--------|-------|
| Sodium | ✅ | No changes |
| Iris | ✅ | Same as FSR 2 |
| Shader packs | ✅ | Same as FSR 2 |
| Distant Horizons | ✅ | Same as FSR 2 |
| MSAA | ✅ | Resolve before EASU |
| SSAA | ✅ | SSAA takes priority |

---

## File Structure (New Files)

```
src/main/java/net/cr512/modernopt/render/
├── FSR31Pass.java           // compute/fragment dispatch logic

src/main/resources/assets/modernopt/shaders/core/
├── fsr31_easu.comp          // compute shader
├── fsr31_easu.fsh           // fragment fallback
├── fsr31_rcas.comp
├── fsr31_rcas.fsh
├── fsr31_denoise.comp       // optional
├── fsr31_denoise.fsh        // optional
├── fsr31_reactivity.comp    // optional
└── fsr31_reactivity.fsh     // optional
```

---

## Implementation Checklist

### Core
- [ ] Port EASU compute + fragment from FidelityFX SDK
- [ ] Port RCAS compute + fragment  
- [ ] Add `UpscalerType.FSR31` enum + config fields
- [ ] Implement `reconstructFsr31()` in `RenderPipeline`
- [ ] Compute dispatch with fragment fallback
- [ ] Config screen: sharpness slider + denoise/reactivity toggles
- [ ] Lang files

### Optional (Phase 2)
- [ ] Denoiser port + integration
- [ ] Reactivity mask + history buffer integration
- [ ] Auto-detect compute support + fallback

### Polish
- [ ] Benchmark on NVIDIA/AMD/Intel
- [ ] Compare FSR 1/2/31 screenshots
- [ ] Fix edge cases (UI, HUD, Iris)
- [ ] Update README/Changelog

---

## Effort Estimate

| Task | Weeks |
|------|-------|
| Shader port (EASU+RCAS) | 1.5 |
| Pipeline integration | 0.5 |
| Config/UI | 0.5 |
| Testing/polish | 1.0 |
| **Total** | **3.5** |

**Optional additions**: +1 week each for Denoiser / Reactivity.

---

## Why This Over FSR 2?

| Aspect | FSR 2 | FSR 3.1 |
|--------|-------|---------|
| Edge reconstruction | Good | **Better** (improved EASU) |
| Ringing artifacts | Visible at high sharpness | **Reduced** (improved RCAS) |
| Temporal stability | Full temporal pass | **Optional reactivity** (lighter) |
| Noisy inputs | Artifacts | **Denoiser option** |
| Compute path | No | **Yes** (faster on modern GPU) |
| Frame Gen | No | No (same) |

---

## Verdict

**Do it.** 3-4 weeks, pure upscaler, better quality, zero risk, builds on existing FSR 2 infra. Perfect next step for `feature/fsr2` branch.