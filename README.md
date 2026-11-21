# ModernOPT

Render scale, upscalers and anti-aliasing for **Minecraft 1.21.1 / NeoForge**.

A small, self-contained render-scaling implementation: the world is rendered into
an off-screen target at a lower (or higher) internal resolution, and a reconstruction filter
brings it back to the window resolution. The HUD, chat and every screen stay at native
resolution.

## Features

| Option | Values | What it does |
| --- | --- | --- |
| Render scale | 25 - 100 % | Internal world resolution. 75 % means roughly 56 % of the pixels. |
| Upscaler | `Bilinear`, `Nearest`, `Soft scale`, `FSR 1`, `MSR`, `MSR2` | Filter used to present the internal image. `MSR` adds a temporal accumulation pass. `MSR2` is the newest upscaler, built on the same camera-reconstructed reprojection as MSR but with an output-resolution RGBA16F history and a depth-guided Lanczos reconstruction. |
| SoftScale softness | 0 - 100 % | Blur radius of the 9-tap tent filter (`Soft scale`). |
| FSR sharpness | 0 - 100 % | RCAS strength applied after the FSR 1 EASU or MSR temporal upscale. |
| MSAA | Off, 2x, 4x, 8x, 16x | Multisampling for the world pass (hardware, resolved before the upscale). |
| SSAA | On / Off | Renders the world at 2x resolution and downsamples with a 2x2 box filter. |
| FXAA | On / Off | Post-process edge smoothing on the final native-resolution image. |
| FP16 world target | On / Off | Render the pre-upscale image into RGBA16F (HDR) instead of RGBA8. |
| Scale whole window | On / Off | Render the whole frame (world, HUD, presentation) at the internal resolution, including the HUD and the presentation. |
| Shadow map resolution | 512, 1024, 2048, 4096 | Absolute size of the Iris shader pack's shadow map, independent of the render scale. |
| Status overlay | On / Off | FPS, internal resolution, active filter, MSAA level, shadow held ratio and interval. |

Anti-aliasing order is always *world -> SSAA downsample -> reconstruction filter -> FXAA*, so the
options compose instead of fighting each other.

### MSR

`MSR` is a temporal upscaler in the same shape as AMD's FSR 2: the world is rendered with a
sub-pixel Halton jitter, EASU brings the current frame to output resolution, a temporal pass
reprojects the previous frame with the camera matrices and accumulates it in an RGBA16F history
buffer, and RCAS sharpens the result (FXAA can follow). A depth test rejects disoccluded pixels,
and a 3x3 YCoCg neighbourhood clamp plus a reactive mask suppress ghosting.

It is not a byte-for-byte port of AMD's FSR 2 shaders. Motion vectors are reconstructed from the
camera only, so moving entities, particles and the hand rely on the clamp/reactive rejection
instead of per-object velocities; fast objects can leave a short trail. The first frame after a
world load (FOV still 0, degenerate projection) is skipped and the history is reset.

MSR runs at 100 % too, where it acts as temporal anti-aliasing. SSAA has priority: enabling
SSAA turns MSR off. With an Iris shader pack active, ModernOPT disables its own projection
jitter and lets the pack's temporal filter drive sub-pixel jitter; MSR still reprojects the
pack's output and upscales it. MSAA is also forced off in MSR mode and that is logged once.

### MSR2

`MSR2` is the newest upscaler in ModernOPT. It uses the same camera-reconstructed motion vectors
as MSR, but merges the passes: a temporal pass with variance clipping in YCoCg followed by an
RCAS sharpen pass.

Key properties:
- History is **RGBA16F colour + R16_UINT depth**, allocated at the **output resolution**. The
  temporal pass therefore keeps the detail it accumulates instead of throwing it away before the
  final upscale. At 1080p the colour pair is about 32 MiB and the depth pair about 8 MiB, so the
  ping-pong history costs roughly 40 MiB.
- The temporal pass runs as a compute shader on GL 4.3+ and dispatches one thread per output
  pixel; the final pass is RCAS, or a plain copy when sharpening is off.
- MSR2 applies an R2 sub-pixel jitter and samples the history on the unjittered output grid, so
  accumulated samples do not drift by the difference between jitter phases. History that moves more
  than 15 % of the screen in one frame is rejected.
- The low-resolution current frame is reconstructed with **depth-guided Lanczos-2** before
  temporal accumulation (toggle: `msr2.lanczos`, default on). The kernel weights come from a
  65-phase polyphase table in a small R32F texture, and the same taps supply the min/max box for
  temporal acceptance, so the acceptance window is exactly as wide as the reconstruction
  footprint. When scene depth is unavailable the per-tap depth fetch is skipped, not just its
  weight.
- Negative values and NaNs are clamped before `imageStore`, so one bad frame cannot poison the
  ping-pong history.
- `msr2.lanczos3` switches to the wider Lanczos-3 kernel: sharper texture response and more
  ringing on very high-contrast edges.
- GPUs without a usable GL 4.3 compute program fall back to the spatial MSR2 shader; there is no
  temporal accumulation on that path.
- Iris integration: shadow viewports are preserved. If Iris does not expose its depth texture,
  MSR2 uses the scaled target's depth for reprojection instead of disabling temporal history.
- Falls back to FSR 1 / MSR automatically if the shaders fail to compile or the GPU errors.
- Dynamic resolution scaling (DRS) is available: render scale is lowered when FPS drops below
  the configured threshold and raised again when stable.

### Temporal 42 % preset

The Upscaling tab has a one-click **Temporal 42 %** preset: it selects MSR2 and sets the internal
resolution to 42 %. It is **experimental and not recommended as a default yet**. In the controlled
run below its mean was about 5 % above FSR 1 at 50 %, but its 1% low was lower (91.0 vs 98.6 fps)
and the image is softer; the output-resolution temporal pass costs most of what the lower world
resolution saves. The preset only writes the existing `renderScale.percent`, `msr2.enabled` and
`upscaler` values, so it is easy to A/B in your own scene.

### FP16 world target

`renderScale.fp16ColorTarget` (default off) allocates the pre-upscale colour attachment as
`RGBA16F` instead of `RGBA8`. Shader packs produce HDR values and tonemap at the end of their own
chain, so with an 8-bit buffer the image is quantised *before* the upscaler sees it - visible as
banding in sky gradients and dark scenes, and as slightly harsher sharpening in the FSR/MSR upscalers.

The target doubles the bandwidth of the pre-upscale colour buffer. The buffer is the small, scaled
one, so the cost is usually small, but it is scene- and GPU-dependent; measure it with the harness
rather than assuming it is free. The win is image quality: the upscaler reads the same HDR range
the pack rendered.

## How it works

```
GameRenderer#renderLevel (HEAD)
  plain modes (bilinear/nearest/soft/FSR 1)
                                       -> install a minimal colour+depth target in the real
                                          Minecraft#mainRenderTarget field, bind and clear it
  advanced modes (MSAA/FP16/SSAA/MSR/MSR2) -> bind the scaled target and redirect
                                          Minecraft#getMainRenderTarget to it
   world, entities, particles, weather -> drawn into the internal target
GameRenderer#render before Gui#render   -> restore the real target / drop the redirect,
                                          run the upscaler (+ optional FXAA) into the real
                                          main target, bind it again
Gui#render                              -> native resolution, untouched
```

Key points:

* Plain modes do not redirect `Minecraft#getMainRenderTarget()` at all: the field already
  points at the small target, which removes a getter hook from Sodium's and Iris' hottest
  path. The HUD keeps its own optional redirect, and advanced modes retain the call-time one
  because their MSAA/FP16/temporal plumbing is built around the scaled target.
* The fast target is a minimal `TextureTarget` (colour + depth), with no multisampling, HDR
  attachment or history buffers. Advanced modes keep the existing `ScaledRenderTarget`.
* The world target is a real `RenderTarget` (colour + depth texture), so anything that samples
  the depth buffer still finds one.
* MSAA uses a separate multisampled framebuffer and resolves only the colour buffer; the depth
  buffer of the single-sampled target is what other code reads.
* The spatial upscaler shaders are ordinary Minecraft core shaders
  (`assets/modernopt/shaders/core/*.json`) created through `ShaderInstance`, so they are
  reloaded with F3+T like any resource-pack shader. The MSR2 temporal pass is a raw compute
  program compiled once from the same resource tree, not a `ShaderInstance`.

## Compatibility

* **Sodium** - no rendering conflict by design (plain modes swap the main target field, advanced
  modes redirect `getMainRenderTarget()`). The Sodium video settings get a **ModernOPT** page that
  opens the full options screen, plus a native **Settings** page with the common options. This uses
  Sodium 0.8.13's config API and its `sodium:config_api_user` mod property, the same mechanism Iris
  uses. Sodium is a compile-only dependency: its classes are only touched by
  `ModernOptSodiumConfig`, which Sodium itself loads, so the mod still starts normally when Sodium
  is absent.
* **Iris** - supported. Iris sizes its gbuffer and depth targets from
  `Minecraft#getMainRenderTarget()`, so both the field swap and the advanced redirect scale the
  whole shader pipeline, not just the vanilla world pass. While a shader pack is active MSAA is forced off (the pack owns
  its multisampled buffers); render scaling, the upscaler and FXAA still apply. Iris caches the
  depth texture it attaches to its framebuffers behind a version counter, so the scaled target
  exposes its own counter through the `Blaze3dRenderTargetExt` methods. That keeps depth correct
  when the game switches between the vanilla and the scaled target, including after a resize.
  Iris also drives the GL viewport itself for its shadow map and its half resolution composite
  passes, and vanilla rebinds the world target afterwards without resetting it; the scaled target
  therefore re-applies its own viewport whenever it is bound (and rejects window sized viewports
  while it is the draw target), otherwise entities and the first person hand are rasterised for
  the window size and clipped out of view. Iris tracks whether the main target is bound by
  injecting at the end of `RenderTarget#bindWrite`; the scaled target therefore runs the vanilla
  bind first and only then redirects to its multisampled buffers. That keeps Iris' gbuffer shaders
  (including `gbuffers_entities`) enabled while the world is scaled - without it, entities were
  drawn with vanilla shaders and then overwritten by the composite, so they disappeared or looked
  transparent. Pack detection is reflective and tries both
  `isShaderPackInUse()` and `isPackInUseQuick()`, so Iris is optional and nothing is compiled
  against it.
* **Fabulous graphics** - MSAA is skipped (it needs a single-sampled depth buffer); render
  scaling itself still works.
* **LOD renderer** - rendered through the same redirected target, so LODs scale together with
  the rest of the world.

## Limitations (honest list)

* MSAA is disabled while an Iris shader pack is active; the pack brings its own antialiasing.
* A shader pack owns its lighting pipeline, so effects it computes at internal resolution
  (shadows, SSAO, reflections) are also at internal resolution. That is the point of the feature,
  but it means very low scales soften those effects too.
* The upscalers are presentation filters. They cannot recover detail that was never rendered,
  which is why FSR 1 looks best at 67-85 % scale.
* MSR and MSR2 use camera-reconstructed motion vectors. They have no per-object velocities, so fast
  moving entities and particles can ghost briefly before the neighbourhood clamp rejects them.
  Under an Iris shader pack, depth is read from the pack's gbuffer when Iris exposes it. MSR falls
  back to rotation-only reprojection for that frame and logs a warning once; MSR2 uses the scaled
  target's depth instead.
* Under a heavy shader pack, MSR and MSR2 cannot make the pack's fixed full-resolution passes
  cheaper. Complementary's shadow map, composite and post chain keep the GPU busy even at 25 %
  render scale; the upscaler only scales the gbuffer/world pass. Reaching a much higher frame rate
  can therefore require lowering the pack's shadow or post settings, not just the render scale.
* The status overlay is hidden while the F3 debug screen is open, because both use the top-left
  corner; F3 already reports the frame rate.

## Testing

The methodology, the two pinned mod stacks and the state suffixes are in
[TESTING.md](TESTING.md). All README numbers below come from the same scene and the same harness:
RTX 5060 Ti 16 GB / Ryzen 7 7800X3D, 1920x1080, Complementary Unbound r5.9.3, render distance 16,
frozen time and weather, static camera, 512 shadow map, 60-tick warmup and 160-tick sample. The
control stack is Minecraft 1.21.1, NeoForge 21.1.264, Sodium 0.8.13 and Iris 1.8.14-beta.1.

| Mode | Mean FPS | 1% low | Shadow interval | Held share | Shadow ms (drawn / amortized) |
| --- | ---: | ---: | ---: | ---: | ---: |
| Native (mod disabled, 100 %) | 97.2 | 76.4 | - | - | - |
| FSR 1, 50 % | 130.1 | 98.6 | 2 | 50 % | 2.63 / 1.32 |
| FSR 1, 50 % | 111.0 | 94.0 | 1 | 0 % | 2.58 / 2.58 |
| MSR2, 50 % | 129.0 | 100.0 | 2 | 50 % | 2.62 / 1.31 |
| MSR2, 42 % | 136.4 | 91.0 | 2 | 50 % | 2.62 / 1.31 |

`Held share` is `held / (drawn + held)` for the same measurement window as the stage times; the
interval is printed next to it because 50 % is the correct value for interval 2, not a defect.
The 1% low is the mean of the slowest 1 % of frames after excluding the screenshot frame. Absolute
FPS depends on GPU state; compare rows from this one run, not numbers from older releases.

MSR2 50 % is within 1 % of FSR 1 in this CPU-limited scene. MSR2 42 % has a higher mean (about
5 %) but a lower 1% low (91.0 vs 98.6), so the 42 % preset is not presented as a universal win.
The shadow interval is the larger lever: interval 2 cuts the amortized shadow cost in half and is
worth about 17 % mean FPS here.

`nvidia-smi` sampled during a separate run of the same scene reported 79-83 % GPU utilization
while the frame rate was capped (2.7 GHz, ~95 W). That is below the 90 % threshold used for this project, so the render thread is
the current floor in this scene: another GPU pass will not convert into FPS one-to-one. CPU-side
work (draw submission, allocations, per-frame lookups) is the next priority, not more pixel work.
The sampler itself adds hitches, so it is never run during a timing run.

Correctness checks that accompany these numbers: render scale 50-75 % with bilinear, FSR 1 and
MSR2, correct depth (no holes or LODs over the world), HUD at native resolution, first-person hand
and nearby entities in place under an active shader pack, no OpenGL errors on resize or pack
reload, and a static-camera MSR2 run whose frame-10 to frame-60 MAE is 0.009-0.012. The residual is
the 15 % current-frame term of the fixed 0.85 history blend, not divergence: it drops from frame 1
to frame 10 and then stays flat, and the difference image shows per-pixel grass noise rather than a
moving image. Against a native-100 reference of the same frozen scene, MSR2 42 % measured MAE 0.051
below the sky (the full-frame number includes moving clouds) and a detail ratio of 0.71, i.e. the
expected loss of high-frequency detail at a 42 % internal resolution. Motion runs (`!pan2`,
`!walk`) are inspected for trails on mobs, flowers and contraptions.

## Configuration

`config/modernopt-client.toml` (created on first launch), or the in-game screen:

* `Options -> ModernOPT` on NeoForge's mod list, or
* `Video Settings -> ModernOPT` in Sodium (the full screen or the native `Settings` page), or
* the `F10` keybind (in game).

The full screen is split into **Upscaling**, **Anti-aliasing** and **Shadows** tabs.

`renderScale.dynamicMovingResolution` lowers the configured internal resolution by 15% while the
camera is moving or looking around (for example 70% -> 60%) and restores it after a short
stillness period. The percent is rounded to a whole number.

### Scale whole window

`renderScale.scaleWholeWindow` (off by default) renders the entire frame at the internal
resolution: the world, the hand, the HUD and Iris' composite chain all run at the scaled size. The
real main render target is resized to the internal resolution, so vanilla's own present blit
stretches it to the window - no extra copy pass. This is the standard full-frame approach.

This mode was verified functionally (world, hand, HUD and Iris composite at the internal size, then
one present blit) and is included in the benchmark state list with the `!wws` suffix. Its throughput
must be measured with the same harness as every other row; older numbers from a different scene are
not reproduced here.

Two things had to be handled for this mode: Iris cancels vanilla's hand pass, so the hand is drawn
by ModernOPT (as in the scaled mode), and the viewport repair now also covers the small main
target (a window-sized viewport leaking onto it produced floating geometry fragments).

World-only mode keeps the HUD pixel sharp and stays the default.

### Shadow map resolution

Iris shader packs hardcode their shadow map size (`const int shadowMapResolution = 2048;` in
Complementary), and that size is independent of the render scale: a 50 % world still pays for a
2048x2048 shadow pass. `shadows.shadowMapResolution` overrides it with 512, 1024, 2048 or 4096.

The override is applied in two places so the buffer and the shader math never disagree:

* `ShadowRenderTargets` is constructed with the configured resolution (`@Pseudo` Iris mixin), which
  sizes the depth texture, the colour buffers and the shadow viewport;
* the `shadowMapResolution` constant in the pack source is rewritten while Iris preprocesses it,
  so texel offsets, PCF taps and blur radii match the real map.

Both run once per pack load. Changing the value reloads the shader pack once (in the options screen
or by editing the config); nothing is ever recreated per frame. Packs that derive the size from
`textureSize(shadowtex0, 0)` need no rewrite and are left untouched.

Lower resolutions reduce VRAM (a 2048 depth map is 16 MB, 512 is 1 MB) and shadow quality, but on
this GPU the shadow pass is draw-call bound, so expect ~0.1-0.4 ms rather than a large frame-time
win - see the benchmark notes below.

### Shadow update interval

`shadows.updateInterval` (1-8, default 2) rebuilds the shadow map every N frames instead of every
frame. The frames in between skip the whole second world pass from the sun and keep sampling the
previous map. The held light matrices are the drawn frame's matrices, translated by the camera
delta in world space before the light transform; a camera move above 1.5 blocks, a world-time jump
above 40 ticks, or a tracked caster moving above 0.5 blocks forces an immediate rebuild. Drops,
arrows and experience orbs are not tracked as casters, and the caster radius is the pack's own
entity shadow distance rather than a fixed 128 blocks. If Iris ever stops tagging its shadow clear
passes, the hold disables itself with a single warning instead of silently flickering.

Measured in the same scene as the table above (FSR 1, 50 %, 512 shadow map):

| Interval | Mean FPS | 1% low | Held | Shadow amortized |
| ---: | ---: | ---: | ---: | ---: |
| 1 (every frame) | 111.0 | 94.0 | 0 % | 2.58 ms |
| 2 | 130.1 | 98.6 | 50 % | 1.32 ms |

The full profile line reports both the drawn-frame cost and the amortized value, and the overlay
shows `shadows held N/M (%) i=N`. With interval 1 the pass is still timed and counted as drawn.

Screenshots at interval 1 and 2 show the same shadow placement; the remaining pixel differences
come from the pack's animated cloud shadows.

## Building

Requires JDK 21.

```bash
gradle build
```

The jar lands in `build/libs/modernopt-<version>.jar`. Sodium 0.8.13 is downloaded from Modrinth's
Maven repository at build time only; the `extractSodiumInnerJar` task unpacks Sodium's nested mod
jar so the optional config API can be compiled against. The included GitHub Actions workflow does
exactly this on every push and uploads the jar as an artifact.

## Credits

* FSR 1 EASU and RCAS are a port of AMD's
  [FidelityFX Super Resolution 1](https://github.com/GPUOpen-Effects/FidelityFX-FSR) reference
  implementation (MIT). The reference fetches 2x2 quads with `gather4`; this port fetches each tap
  individually, which is equivalent on any GL 3.2+ driver.
* The MSR temporal upscaler is an original implementation inspired by AMD's
  [FidelityFX Super Resolution 2](https://github.com/GPUOpen-Effects/FidelityFX-FSR2) design
  (jitter, reprojection, history clamping, RCAS). It shares no code with the FSR 2 reference.
* FXAA follows Timothy Lottes' original luma-based algorithm.

## License

ModernOPT is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE), [NOTICE](NOTICE) and
[THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt) (also shipped in the jar under `META-INF`).
MSR2 is part of the same Apache-2.0 codebase: there is no separate grant to revoke later. That is a
deliberate choice while the project is building adoption; a paid/closed split would have to start
from a future, separately written component, not from code already released here.

