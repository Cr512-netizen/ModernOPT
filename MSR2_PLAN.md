# MSR 2.0 (Minecraft Super Resolution) — Technical Assessment & Implementation Plan

**Based on provided ТЗ v2.0** | Target branch: `feature/msr2` (new, separate from `feature/fsr2`)

---

## Executive Summary

| Aspect | Assessment |
|--------|------------|
| **Architectural coherence** | ✅ Strong — 2-pass design eliminates EASU+RCAS separation, reduces state changes |
| **Format choices** | ✅ Excellent — R11G11B10F + R16_UINT saves 50% VRAM vs RGBA16F |
| **Variance clipping** | ✅ Superior to 5-point clamp — statistical approach handles noise better |
| **TBDR-aware spatial pass** | ✅ Critical for iGPU/mobile — fragment shader avoids tile flushes |
| **SSBO motion vectors** | ⚠️ High complexity — requires entity tracking rewrite |
| **Compute-only temporal** | ⚠️ GL 4.3+ hard requirement — breaks older hardware support |
| **1ms / 16MB targets** | ⚠️ Aggressive — achievable on desktop, **unlikely on iGPU** with compute overhead |
| **Iris G-buffer integration** | ❌ Speculative — Iris API doesn't expose motion vectors reliably |
| **Entity SSBO pool** | ⚠️ GC-safe but complex — needs careful lifecycle management |

**Verdict**: **Technically solid but over-scoped for v1**. Recommend **phased approach**: Phase 1 = TBDR-aware spatial pass + variance clipping (immediate win), Phase 2 = compute temporal + SSBO motion vectors (later).

---

## Detailed Technical Assessment

### ✅ Strengths

| Item | Why It Works |
|------|--------------|
| **2-pass merge** | EASU→RCAS→Temporal is 3 passes + 2 barriers. Temporal(Compute)→Spatial(Fragment) = 2 passes, 1 barrier. |
| **R11G11B10F + R16_UINT** | 48 bits/px vs 64 (RGBA16F) or 128 (RGBA32F). 16MB budget realistic at 1080p. |
| **Variance clipping (YCoCg)** | Statistically sound, handles stochastic noise (foliage, water) better than fixed neighborhood clamp. |
| **Fragment spatial pass** | On TBDR (Adreno, Mali, Apple GPU, Intel iGPU), compute shaders force tile flush → fragment keeps data in tile memory. |
| **SSBO + pool allocator** | Eliminates GC spikes — correct architectural decision. |
| **Dynamic resolution + temporal fallback** | Graceful degradation under load — production-ready thinking. |

### ⚠️ Risks & Mitigations

| Risk | Severity | Mitigation |
|------|----------|------------|
| **GL 4.3 compute requirement** | High — breaks macOS (stuck on 4.1), older Linux mesa, some Windows drivers | **Phase 1**: fragment fallback for temporal pass; Phase 2: compute path behind capability flag |
| **Iris motion vectors** | High — Iris doesn't expose motion vectors in public API; G-buffer format is internal | **Mitigation**: Camera-reprojection fallback (current FSR 2 method) when Iris active; document as limitation |
| **Entity SSBO complexity** | Medium — requires entity ID tracking, screen-space projection, SSBO update logic | **Mitigation**: Start with camera-only motion vectors (works for static geometry); add entity SSBO in Phase 2 |
| **1ms on iGPU** | High — compute dispatch + fragment pass + barrier ≈ 1.5-2ms on Iris Xe @ 1080p | **Mitigation**: Target 2ms initially; optimize workgroup size (16×16), reduce 3×3 variance to 3×1 separable |
| **SSBO update cost** | Medium — `glBufferSubData` every frame can stall | **Mitigation**: Persistent mapped buffer (GL_ARB_buffer_storage) + `glFenceSync` double-buffer |
| **No RGBA32F history** | Low — R11G11B10F lacks alpha; temporal needs depth in alpha | **Fix**: Pack depth in R16_UINT alongside color (as specified) — works |

### ❌ Speculative / Unproven Claims

| Claim | Reality |
|-------|---------|
| "Iris vectors intercepted from G-buffers" | **False** — Iris G-buffer layout is not public; changes per shader pack. No stable API. |
| "1ms on Iris Xe @ 1080p" | **Unlikely** — compute dispatch overhead ~0.3ms, 3×3 variance sampling 9 texture reads × 2M pixels = bandwidth bound. Realistic: 2-3ms. |
| "Zero input latency" | **True for camera** — but entities animate at real frame rate (same as FSR 2/FrameGen). |
| "No neural networks" | ✅ True — all heuristic. |

---

## Phased Implementation Plan

### Phase 1: "TBDR Spatial + Variance Temporal" (3-4 weeks)
**Goal**: Immediate quality/perf win on iGPU/mobile, zero compute requirement.

| Week | Deliverable |
|------|-------------|
| 1 | **Variance clipping temporal pass** (fragment shader) — replace 5-point clamp in `temporal.fsh` with 3×3 YCoCg variance clipping. Ping-pong HalfFloatTarget stays. |
| 2 | **TBDR-aware spatial pass** — merge EASU+RCAS into single fragment shader (`msr_spatial.fsh`) with edge-aware interpolation + depth-modulated CAS. Output directly to default framebuffer via blit/quad. |
| 3 | **Format migration** — Switch history buffers to R11G11B10F color + R16_UINT depth (packed). Update `HalfFloatTarget` → `MSRHistoryTarget`. |
| 4 | **Config + testing** — `msrSharpness`, `msrVarianceScale`, `msrEdgeAware` toggles. Benchmark on Iris Xe / Adreno / Mali. |

**Outcome**: ~1.5ms on iGPU, better quality than FSR 2, works on GL 3.2+, TBDR-friendly.

---

### Phase 2: "Compute Temporal + SSBO Motion" (4-6 weeks)
**Goal**: Full ТЗ compliance — compute temporal, SSBO motion vectors, entity tracking.

| Week | Deliverable |
|------|-------------|
| 1-2 | **Compute temporal shader** (`msr_temporal.comp`) — variance clipping in compute, 8×8 workgroups, subgroup ops for variance reduction. Fragment fallback for GL < 4.3. |
| 2-3 | **MotionVectorCollector** — CPU-side pool allocator (`EntityMotionPool`), per-entity screen-space velocity, SSBO (`R16G16_SFLOAT`). Camera + entity vectors. |
| 3-4 | **Iris integration** — Attempt G-buffer depth/normal read via reflection; fallback to camera reprojection. Document limitation. |
| 4-5 | **Persistent SSBO** — `GL_ARB_buffer_storage` + `glFenceSync` double-buffer for async update. |
| 6 | **Dynamic resolution + temporal fallback** — FPS monitor, resolution steps, temporal disable < 40 FPS. |

**Outcome**: Full MSR 2.0 spec, ~2ms on desktop, ~3ms on iGPU (compute), 16MB VRAM.

---

### Phase 3: "Polish & Ecosystem" (2 weeks)
- Distant Horizons LOD detection (disable edge-aware for far chunks)
- HUD caching (blit cached HUD on generated frames)
- Diagnostic overlay (GPU timers, VRAM usage, variance heatmap)
- Modrinth/CurseForge release prep

---

## File Structure (New)

```
src/main/java/net/cr512/modernopt/render/
├── MSRHistoryTarget.java          // R11G11B10F + R16_UINT ping-pong
├── MSRTemporalPass.java           // Compute + fragment fallback
├── MSRSpatialPass.java            // Fragment shader (TBDR)
├── MSRComputeTemporalPass.java    // Phase 2: compute-only
├── MotionVectorCollector.java     // Phase 2: SSBO + pool
├── EntityMotionPool.java          // Phase 2: GC-free pool
├── MSRConfig.java                 // Config mirror pattern
└── MSRRuntime.java                // Orchestration

src/main/java/net/cr512/modernopt/mixin/
├── MSRLevelRendererMixin.java     // Capture + cancel hooks
├── MSREntityRendererMixin.java    // Phase 2: entity screen pos
└── MSRIrisCompat.java             // Phase 2: G-buffer reflection

src/main/resources/assets/modernopt/shaders/core/
├── msr_temporal.fsh               // Phase 1: fragment variance clipping
├── msr_temporal.comp              // Phase 2: compute variance clipping
├── msr_spatial.fsh                // Phase 1: fused EASU+CAS+edge-aware
├── msr_motion_vector.comp         // Phase 2: velocity encode (optional)
└── msr_variance.glsl              // Shared YCoCg + variance utils
```

---

## Compatibility Matrix (Target)

| Feature | Phase 1 | Phase 2 |
|---------|---------|---------|
| **GL version** | 3.2+ | 4.3+ (compute), 3.2+ fallback |
| **Iris** | ✅ Camera reprojection | ⚠️ Best effort G-buffer |
| **Sodium** | ✅ | ✅ |
| **Distant Horizons** | ✅ Bilinear for LOD | ✅ |
| **iGPU (Iris Xe/780M)** | ✅ ~1.5ms | ⚠️ ~3ms compute |
| **Mobile (ARM/Adreno)** | ✅ TBDR fragment | ❌ No compute |
| **macOS** | ✅ | ⚠️ No compute (Metal) |

---

## Configuration Additions

```java
// ModernOptConfig.java
MSR_ENABLED = define("msrEnabled", false);           // Off by default (experimental)
MSR_VARIANCE_SCALE = defineInRange("msrVarianceScale", 2.0, 0.5, 4.0);  // YCoCg variance multiplier
MSR_EDGE_AWARE = define("msrEdgeAware", true);       // Depth-gradient interpolation
MSR_SHARPNESS = defineInRange("msrSharpness", 30, 0, 100);
MSR_COMPUTE_TEMPORAL = define("msrComputeTemporal", false);  // Phase 2
MSR_ENTITY_MOTION = define("msrEntityMotion", false);  // Phase 2
MSR_DYNAMIC_RES = define("msrDynamicRes", true);
MSR_TEMPORAL_MIN_FPS = defineInRange("msrTemporalMinFps", 40, 20, 60);
```

---

## Performance Budget (1080p)

| Component | Phase 1 (Fragment) | Phase 2 (Compute) |
|-----------|-------------------|-------------------|
| Temporal (variance) | 0.8ms | 0.6ms (compute) + 0.3ms dispatch |
| Spatial (fused) | 0.7ms | 0.7ms |
| Motion vectors | 0.1ms (camera) | 0.2ms (SSBO update) |
| Barriers/sync | 0.1ms | 0.2ms |
| **Total** | **~1.7ms** | **~1.8ms** |
| **VRAM** | 12MB | 16MB |

> Note: 1ms target is **aspirational** — 1.5-2ms is realistic for iGPU. Desktop dGPU will hit 0.5-0.8ms.

---

## Recommendation

**Start with Phase 1 immediately** — it delivers 80% of the visual quality improvement (variance clipping + fused spatial) with 20% of the engineering effort, works on all hardware including Mac/iGPU, and integrates cleanly into existing `RenderPipeline`.

**Phase 2** is a valid long-term goal but should be gated behind `msrComputeTemporal` config flag and developed in a separate branch (`feature/msr2-compute`).

**Do not attempt full ТЗ in one go** — the SSBO entity tracking + Iris G-buffer + compute temporal + dynamic resolution is 3-4 months of work with high integration risk.

---

## GitHub Action

```bash
# Create branch
git checkout -b feature/msr2

# Add plan
git add MSR2_PLAN.md
git commit -m "docs: MSR 2.0 technical assessment & phased plan"
git push origin feature/msr2
```

---

*Assessment based on ТЗ v2.0, ModernOPT codebase (FSR 2, FrameGen refs), and OpenGL 4.3/3.2 constraints. Ready for team review.*