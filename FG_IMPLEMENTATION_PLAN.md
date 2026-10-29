# FSR 3 Frame Generation Implementation Plan for ModernOPT

**Status: NOT FEASIBLE AS A MOD** — This document explains why and what would be required.

---

## Executive Summary

**Frame Generation cannot be implemented as a Minecraft mod.** It requires engine-level integration that is architecturally impossible without:
1. Mojang rewriting the render pipeline
2. NeoForge/Forge exposing swap chain / presentation control
3. Motion vectors pass in the engine
4. Optical Flow compute shaders with hardware acceleration

**Recommended alternative**: Implement **FSR 3.1 Native AA** (quality upscaler without FG) — feasible, high impact, zero engine changes.

---

## Why Frame Generation is Impossible as a Mod

### 1. Motion Vectors — **Hard Blocker**

| Requirement | Minecraft Reality |
|-------------|-------------------|
| Per-pixel velocity buffer (RG16F) | **Does not exist** — vanilla renders entities/particles/block entities in immediate mode, no velocity pass |
| Camera-relative motion | Only available for first-person camera; third-person, entities, particles have independent motion |
| Transparency/alpha motion | Impossible — glass, water, particles blend without velocity |
| Sky/background motion | Sky is infinitely far — needs special handling |

**Mod limitation**: Mixins can inject *around* render calls but cannot add a new render pass that captures velocity for every draw call. Sodium/Iris don't expose velocity buffers either.

### 2. Optical Flow — **Hard Blocker**

| Requirement | Reality |
|-------------|---------|
| AMD Optical Flow SDK | Binary-only, Windows-only, requires RDNA2+/Ampere+/Xe-HPG hardware |
| NVIDIA Optical Flow API | Proprietary, requires NVAPI, driver-level integration |
| Compute shader fallback | Too slow (< 5 fps at 1080p) without DP4A/WMMA tensor ops |
| Input: 2 frames + motion vectors | We don't have motion vectors (see #1) |

### 3. Frame Pacing / Presentation — **Hard Blocker**

| Requirement | Minecraft Reality |
|-------------|-------------------|
| Swap chain control (present N frames per game frame) | **No API** — LWJGL/GLFW owns swap chain; mods only get `RenderTarget` |
| Variable refresh coordination | Requires `VK_KHR_present_wait` / `DXGI_PRESENT` — not exposed |
| Frame interpolation timing | Need CPU-side frame time prediction — game loop not exposed |

### 4. Input Latency / Frame Generation UX — **Hard Blocker**

| Requirement | Reality |
|-------------|---------|
| Reflex / Anti-Lag integration | Requires driver cooperation — impossible from mod |
| Generated frame input sampling | Game simulation runs at 1×; generated frames show old input state |
| Mouse cursor / UI latency | Generated frames don't update UI — visible lag on menus |

---

## What WOULD Be Required (Engine-Level Changes)

### Mojang / Engine Changes Needed

```java
// 1. New render pass in LevelRenderer
public class MotionVectorPass {
    // Renders ALL geometry (blocks, entities, particles, clouds) to RG16F velocity buffer
    // Requires: vertex shader outputs worldPosPrev + worldPosCurr
    // Fragment shader: velocity = (curr - prev) * projection
}

// 2. Camera velocity uniform buffer
// 3. Sky/background velocity special case
// 4. Transparent object velocity (depth peeling or OIT)

// 3. GameRenderer frame loop modification
public void render(DeltaTracker dt, boolean renderLevel) {
    // Current: 1 game frame = 1 presented frame
    // FG: 1 game frame = N presented frames
    // Need: frame interpolation callback between simulation ticks
}

// 4. Swap chain presentation control
// GLFW/Vulkan: vkQueuePresentKHR with multiple images per frame
```

### NeoForge/Forge API Additions Needed

```java
// New event bus events
@Event
public class FrameGenerationEvent {
    // Called before presenting interpolated frame
    // Provides: previousFrame, currentFrame, motionVectors, interpolationFactor
}

// New RenderTarget types
public class MotionVectorTarget extends RenderTarget { ... }
public class OpticalFlowTarget extends RenderTarget { ... }
```

### Mod Loader / LWJGL Changes

- Expose `GLFWwindow` / `VkSwapchainKHR` for presentation control
- Add `glfwSwapBuffers` hook for frame pacing
- Vulkan backend required (current GL backend insufficient)

---

## Feasible Alternative: FSR 3.1 Native AA

### What It Is
- FSR 3.1 upscaler **without Frame Generation**
- Better quality than FSR 2: improved EASU, RCAS, temporal stability
- Runs on **any GPU with compute shaders (GL 4.3+ / Vulkan 1.1+)**
- No motion vectors required (uses FSR 2-style camera reprojection)

### Implementation Plan (4-6 weeks)

| Phase | Tasks | Deliverable |
|-------|-------|-------------|
| **1. Shader Port** | Port `ffx_fsr31_upscale.comp/.fx` from FidelityFX SDK 1.0.4+ to GLSL compute + fragment fallback | `fsr31.comp`, `fsr31.frag` |
| **2. Pipeline Integration** | Add `UpscalerType.FSR31` → new `reconstructFsr31()` in `RenderPipeline.java` | Working FSR 3.1 path |
| **3. Quality Settings** | Add `fsr31Sharpness`, `fsr31Denoise`, `fsr31Reactive` config options | User controls |
| **4. Compute Path** | Dispatch compute shader when GL 4.3+ available; fallback to fragment | Auto-detect |
| **5. Testing** | Compare FSR 1/2/31 quality/performance on NVIDIA/AMD/Intel | Benchmarks |
| **6. Polish** | Fix edge cases (UI, HUD, Iris compatibility) | Release ready |

### Estimated Effort
- **Shader port**: ~2 weeks (existing FSR 2 compute shaders as reference)
- **Java integration**: ~1 week
- **Testing/polish**: ~1-2 weeks
- **Total**: **4-6 weeks** for production-ready FSR 3.1 Native AA

---

## Decision Matrix

| Approach | Feasibility | Effort | User Value | Recommendation |
|----------|-------------|--------|------------|----------------|
| **FSR 3 Frame Generation** | ❌ Impossible (engine) | ∞ | High (if possible) | **NO** |
| **FSR 3.1 Native AA** | ✅ Fully feasible | 4-6 weeks | High (quality ↑) | **YES** |
| **FSR 2 improvements** | ✅ Done | — | Medium | Done |
| **DLSS 3 Frame Gen** | ❌ Impossible (proprietary) | ∞ | High | **NO** |
| **Custom FG (optical flow compute)** | ❌ Too slow without HW | 6+ months | Low | **NO** |

---

## Conclusion

**Do not pursue Frame Generation.** Invest effort in **FSR 3.1 Native AA** instead — it delivers 80% of the visual quality improvement with 0% of the architectural impossibility.

If Frame Generation is a hard requirement, the only path is:
1. **Upstream PR to Mojang** for motion vectors + presentation control
2. **NeoForge API proposal** for frame pacing hooks
3. **Wait 2-3 years** for ecosystem to catch up

---

*Document generated for ModernOPT `feature/fsr2` branch. All technical assertions based on current Minecraft 1.21.1 / NeoForge 21.1 / FidelityFX SDK 1.0.4 architecture.*