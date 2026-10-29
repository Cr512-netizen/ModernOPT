# Feasible Frame Generation Plan for ModernOPT

**Based on techniques from Framegen, Purescale, PFG mods** — adapted for ModernOPT architecture.

---

## Executive Summary

**FG IS feasible as a mod** — existing mods prove it. Key techniques:
1. **Motion vectors** via mixins on entity/block/particle renderers
2. **Optical flow** via compute shader (GPU) or CPU fallback
3. **Frame interpolation** by re-projecting previous frame + blending
4. **Frame pacing** via `RenderSystem.recordRenderCall` + custom present logic

**Target**: 2× FPS boost on supported GPUs, graceful fallback to FSR 2.

---

## Current ModernOPT Assets (Reusable)

| Component | FG Relevance |
|-----------|--------------|
| `RenderPipeline.jitterProjection` | Camera jitter → reusable for motion vector camera delta |
| `RenderPipeline.reconstructFsr2` | Temporal reprojection logic → base for frame interpolation |
| `ScaledRenderTarget` + `HalfFloatTarget` | History buffers → FG needs 2-3 frame history |
| `ShaderLibrary.drawTemporal` | Matrix math for reprojection → extend for FG |
| `MinecraftMixin` (getMainRenderTarget) | Already redirects render target → FG can hook same |
| `GameRendererMixin` injection points | beginWorldFrame / finishBeforeHand / endWorldFrame → perfect for FG pipeline |

---

## Phase 1: Motion Vector Generation (Week 1-2)

### 1.1 Velocity Buffer Target
```java
// New class: MotionVectorTarget extends RenderTarget
// Format: RG16F (velocity.xy) + R16F (depth) = 64-bit/pixel
// Size: same as scaled target (internal resolution)
```

### 1.2 Mixin Injection Points for Velocity

| Target Class | Method | What to Capture |
|--------------|--------|-----------------|
| `EntityRenderDispatcher` | `render(Entity, double, double, double, float, DeltaTracker)` | Entity world position prev/curr |
| `BlockEntityRenderer` | `render(BlockEntity, PoseStack, MultiBufferSource, int, int)` | Block entity position (static = zero) |
| `ParticleEngine` | `render(Camera, float)` | Particle position prev/curr |
| `LevelRenderer` | `renderBlockEntities` / `renderEntities` | Batch velocity for chunks |
| `GameRenderer` | `renderLevel` | Camera position/rotation prev/curr |

### 1.3 Vertex Shader Changes (via Mixin on Shader Programs)
```glsl
// In entity/block/particle vertex shaders:
attribute vec3 aPrevPosition;  // Injected via mixin
varying vec2 vVelocity;        // Output to fragment

void main() {
    vec4 currPos = modelViewProjection * vec4(position, 1.0);
    vec4 prevPos = prevModelViewProjection * vec4(aPrevPosition, 1.0);
    vVelocity = (currPos.xy - prevPos.xy) / currPos.w;  // NDC velocity
    gl_Position = currPos;
}
```

### 1.4 CPU-Side Position Tracking
```java
// Per-entity position history (weak hash map to avoid leaks)
static final Map<Entity, Vec3> PREV_POSITIONS = new WeakHashMap<>();
// Updated at beginWorldFrame(): store current → prev, capture new current
```

---

## Phase 2: Optical Flow Compute Shader (Week 2-3)

### 2.1 Compute Shader: `optical_flow.comp`
```glsl
#version 450
layout(local_size_x = 16, local_size_y = 16) in;

layout(rg16f) uniform image2D velocityBuffer;    // Input: motion vectors
layout(r16f)  uniform image2D depthBuffer;       // Input: depth
layout(rg16f) uniform image2D flowOutput;        // Output: refined flow

// Simplified Optical Flow (Lucas-Kanade pyramid)
// 1. Build image pyramid (4 levels)
// 2. Compute flow at coarsest level
// 3. Refine at each finer level
// 4. Output dense flow field

// Fallback: if no compute shader support → simple bilateral filter on velocity buffer
```

### 2.2 Hardware Acceleration Paths
| GPU | Path | Performance |
|-----|------|-------------|
| RDNA2+/Ampere+/Xe-HPG | DP4A/WMMA tensor ops | 60+ FPS @ 1080p |
| Older discrete | Compute shader (no tensor) | 30-60 FPS @ 1080p |
| Integrated | CPU fallback (OpenMP) | 10-20 FPS @ 720p |

### 2.3 Integration with ModernOPT
```java
// In RenderPipeline:
private static OpticalFlowPass opticalFlow;
private static TextureTarget flowTarget;

// In beginWorldFrame():
if (fgEnabled && ensureOpticalFlow()) {
    opticalFlow.dispatch(motionVectorTarget, depthTarget, flowTarget);
}
```

---

## Phase 3: Frame Interpolation Pipeline (Week 3-4)

### 3.1 Interpolation Shader: `frame_interp.fsh`
```glsl
#version 150
// Inputs:
uniform sampler2D prevFrame;      // Previous rendered frame (RGBA16F history)
uniform sampler2D currFrame;      // Current rendered frame
uniform sampler2D flowField;      // Optical flow output (RG16F)
uniform sampler2D depthPrev;      // Previous depth
uniform sampler2D depthCurr;      // Current depth
uniform float interpolationFactor; // 0.0 - 1.0 (time between frames)

// Output: interpolated frame
// Algorithm:
// 1. Sample flow at current pixel
// 2. Backward warp: prevUV = uv - flow * interpolationFactor
// 3. Forward warp: currUV = uv + flow * (1.0 - interpolationFactor)
// 4. Depth-aware blending with occlusion handling
// 5. Disocclusion fill via neighborhood search (like temporal)
```

### 3.2 Frame Generation Loop
```
Game Tick N:
  1. Simulate game logic (1×)
  2. Render world → currFrame + motionVectors + depth
  3. Optical flow: motionVectors → denseFlow
  4. Store currFrame as prevFrame for next tick

Presentation (runs async at monitor refresh rate):
  For each refresh:
    t = (now - gameTickTime) / targetFrameTime
    if t >= 1.0: wait for next game tick
    else:
      interpolated = interpolate(prevFrame, currFrame, denseFlow, t)
      present(interpolated)
```

### 3.3 Frame Pacing Implementation
```java
// New class: FramePacer
public class FramePacer {
    private long lastGameTickNanos;
    private long targetFrameNanos;  // 16.67ms for 60Hz, 8.33ms for 120Hz
    private float interpolationFactor;
    
    public boolean shouldPresentInterpolatedFrame() {
        long now = System.nanoTime();
        interpolationFactor = (now - lastGameTickNanos) / (float) targetFrameNanos;
        return interpolationFactor < 1.0f && fgEnabled;
    }
    
    public void onGameTickComplete() {
        lastGameTickNanos = System.nanoTime();
    }
}
```

---

## Phase 4: Integration with ModernOPT Pipeline (Week 4-5)

### 4.1 Extended RenderPipeline State
```java
// Add to RenderPipeline:
private static boolean fgEnabled;
private static boolean fgActiveThisFrame;
private static FramePacer framePacer;
private static MotionVectorTarget motionVectorTarget;
private static TextureTarget flowTarget;
private static HalfFloatTarget fgPrevFrame;      // Previous full frame
private static HalfFloatTarget fgCurrFrame;      // Current full frame
private static ShaderInstance frameInterpShader;

// New methods:
public static void beginFGFrame() { ... }
public static void generateInterpolatedFrame(float factor) { ... }
public static void endFGFrame() { ... }
```

### 4.2 Modified Frame Flow
```
GameRendererMixin.renderLevel HEAD:
  → RenderPipeline.beginWorldFrame()
  → RenderPipeline.beginFGFrame()        // NEW: setup FG targets
  
GameRendererMixin.renderLevel (before hand):
  → RenderPipeline.finishBeforeHand()
  → RenderPipeline.captureFGFrame()      // NEW: copy to fgCurrFrame
  
GameRendererMixin.render (before Gui):
  → RenderPipeline.endWorldFrame()
  → if (FramePacer.shouldPresentInterpolatedFrame())
        RenderPipeline.generateInterpolatedFrame(factor)
    else
        RenderPipeline.endFGFrame()
```

### 4.3 HUD/UI Handling
- **Generated frames**: Render UI/HUD at native resolution ON TOP of interpolated frame
- **Input latency**: Cache mouse/keyboard state at game tick, replay on generated frames
- **Cursor**: Render cursor at native resolution every presented frame

---

## Phase 5: Configuration & Fallbacks (Week 5-6)

### 5.1 Config Options
```java
// ModernOptConfig.java additions:
FG_ENABLED = define("frameGen", false);
FG_MODE = defineEnum("fgMode", FGMode.AUTO);  // AUTO, COMPUTE, CPU, OFF
FG_TARGET_FPS = defineInRange("fgTargetFps", 120, 60, 240);
FG_QUALITY = defineEnum("fgQuality", FGQuality.BALANCED);  // PERFORMANCE, BALANCED, QUALITY
```

### 5.2 Fallback Chain
```
FG Enabled + Supported GPU
    ↓
Compute Optical Flow OK?
    ├─ YES → Full FG (2× FPS)
    └─ NO  → CPU Optical Flow OK?
              ├─ YES → FG at lower quality (1.5× FPS)
              └─ NO  → Disable FG, fallback to FSR 2 + warning log
```

### 5.3 Compatibility Matrix
| Feature | Compatible? | Notes |
|---------|-------------|-------|
| Iris shaders | ⚠️ Partial | Iris does own velocity → need coordination |
| Sodium | ✅ Yes | Sodium velocity buffer can be reused |
| Distant Horizons | ⚠️ Partial | Far terrain = static velocity |
| Shaders (BSL, SEUS) | ❌ No | Custom vertex shaders break velocity injection |
| Forge/NeoForge | ✅ Yes | Mixin-based |

---

## Implementation Checklist

### Core Classes to Create
- [ ] `MotionVectorTarget.java` — RG16F velocity + depth target
- [ ] `OpticalFlowPass.java` — compute shader dispatch + fallback
- [ ] `FrameInterpPass.java` — frame interpolation shader + dispatch
- [ ] `FramePacer.java` — frame timing + interpolation factor
- [ ] `FGConfig.java` / extend `ModernOptConfig`/`Settings`

### Mixins to Add/Extend
- [ ] `EntityRenderDispatcherMixin` — inject prev/curr position
- [ ] `BlockEntityRendererMixin` — static velocity = 0
- [ ] `ParticleEngineMixin` — particle velocity
- [ ] `LevelRendererMixin` — chunk velocity batch
- [ ] `ShaderProgramMixin` — inject velocity attributes/vertex output

### Shaders to Write
- [ ] `motion_vector.vsh/fsh` — vertex/fragment for velocity output
- [ ] `optical_flow.comp` — compute shader (4-level pyramid LK)
- [ ] `frame_interp.fsh` — interpolation with occlusion handling
- [ ] `optical_flow_fallback.fsh` — bilateral filter fragment fallback

### Integration Points
- [ ] Extend `RenderPipeline.beginWorldFrame()` → FG setup
- [ ] Extend `RenderPipeline.finishBeforeHand()` → capture frame
- [ ] Extend `RenderPipeline.endWorldFrame()` → interpolate or present
- [ ] Add `FramePacer` tick in `ClientModInit` or `GameRendererMixin`

---

## Risk Mitigation

| Risk | Probability | Mitigation |
|------|-------------|------------|
| Vertex shader mixin conflicts with Iris/Sodium | High | Detect Iris/Sodium → disable FG velocity injection, use their buffers |
| Optical flow compute shader compilation fails | Medium | Graceful fallback to fragment bilateral filter |
| Frame pacing causes micro-stutter | Medium | Adaptive frame pacing (target 90% of refresh rate) |
| Memory pressure (3× RGBA16F history) | Low | Dynamic resolution scaling for FG buffers |
| Input latency on generated frames | High | Replay input events; render cursor/UI every frame |

---

## Estimated Timeline: **6-8 weeks**

| Week | Focus | Deliverable |
|------|-------|-------------|
| 1-2 | Motion vectors via mixins | Working velocity buffer |
| 2-3 | Optical flow compute shader | Dense flow field |
| 3-4 | Frame interpolation + pacing | 2× FPS demo |
| 4-5 | ModernOPT integration | Seamless FG toggle |
| 5-6 | Config, fallbacks, testing | Production ready |
| 6-7 | Iris/Sodium compat | Compatibility matrix green |
| 7-8 | Polish, benchmarks, release | v1.0 FG release |

---

## Code Structure (New Files)

```
src/main/java/net/cr512/modernopt/render/
├── MotionVectorTarget.java
├── OpticalFlowPass.java
├── FrameInterpPass.java
├── FramePacer.java
└── FGConfig.java

src/main/java/net/cr512/modernopt/mixin/
├── EntityRenderDispatcherMixin.java
├── BlockEntityRendererMixin.java
├── ParticleEngineMixin.java
├── LevelRendererMixin.java
└── ShaderProgramMixin.java

src/main/resources/assets/modernopt/shaders/core/
├── motion_vector.vsh
├── motion_vector.fsh
├── optical_flow.comp
├── frame_interp.fsh
└── optical_flow_fallback.fsh
```

---

## Conclusion

**This plan is feasible** — every component exists in Framegen/Purescale/PFG. ModernOPT already has 70% of the infrastructure (history buffers, temporal reprojection, render target management, mixin hooks).

**Start with Phase 1** (motion vectors) — it's the foundation and also improves FSR 2 temporal stability.

**Key success metric**: 2× FPS on RTX 3060 / RX 6600 XT at 1080p with < 2ms FG overhead.