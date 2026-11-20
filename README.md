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
| Upscaler | `Bilinear`, `Nearest`, `Soft scale`, `FSR 1`, `MSR`, `MSR2` | Filter used to present the internal image. `MSR` adds a temporal accumulation pass. `MSR2` is the newest upscaler, built on the same camera-reconstructed reprojection as MSR but with a 2-pass design (temporal + fused spatial) and R11G11B10F history buffers for half the VRAM bandwidth. |
| SoftScale softness | 0 - 100 % | Blur radius of the 9-tap tent filter (`Soft scale`). |
| FSR sharpness | 0 - 100 % | RCAS strength applied after the FSR 1 EASU or MSR temporal upscale. |
| MSAA | Off, 2x, 4x, 8x, 16x | Multisampling for the world pass (hardware, resolved before the upscale). |
| SSAA | On / Off | Renders the world at 2x resolution and downsamples with a 2x2 box filter. |
| FXAA | On / Off | Post-process edge smoothing on the final native-resolution image. |
| FP16 world target | On / Off | Render the pre-upscale image into RGBA16F (HDR) instead of RGBA8. |
| Scale whole window | On / Off | Render the whole frame (world, HUD, presentation) at the internal resolution, including the HUD and the presentation. |
| Shadow map resolution | 512, 1024, 2048, 4096 | Absolute size of the Iris shader pack's shadow map, independent of the render scale. |
| Status overlay | On / Off | FPS, internal resolution, active filter, MSAA level. |

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
as MSR, but merges the passes: a temporal pass with variance clipping in YCoCg (3x3 mean +
2-sigma rejection, much better ghosting suppression than the old 5-point clamp) followed by an
RCAS sharpen pass.

Key properties:
- History is stored as **R11G11B10F colour + R16_UINT depth** (48 bits/px vs 64 for RGBA16F),
  and it is allocated at the **output resolution**. The temporal pass therefore keeps the detail
  it accumulates instead of throwing it away before the final upscale.
- The temporal pass runs as a compute shader on GL 4.3+ and dispatches one thread per output
  pixel; the final pass is RCAS, or a plain copy when sharpening is off.
- MSR2 applies an R2 sub-pixel jitter, compensates for the previous frame's jitter phase when
  reading history, and rejects history that moves more than 15% of the screen in one frame. This
  is the temporal anti-aliasing part; MSR keeps jitter disabled under Iris, MSR2 can be tried
  with a pack and falls back to the normal image if it does not pass the quality check.
- The low-resolution current frame is reconstructed with **Lanczos-2** before temporal
  accumulation (toggle: `msr2.lanczos`, default on). The weighted sum is clamped to the source
  neighbourhood, which removes the bright/dark halos from the negative lobes while keeping the
  sharper in-band response. It noticeably reduces the soapy look on grass and foliage, costs about
  2% FPS, and can still ring on very high-contrast block edges; turn it off if the image shimmers.
- The fragment fallback remains for GPUs without compute, but a compute-capable driver is needed
  for the output-resolution history path.
- Iris integration: shadow viewports are preserved, and if the Iris depth buffer cannot be
  read cleanly MSR falls back to a simplified spatial-only mode.
- Falls back to FSR 1 / MSR automatically if the shaders fail to compile or the GPU errors.
- Dynamic resolution scaling (DRS) is available: render scale is lowered when FPS drops below
  the configured threshold and raised again when stable.

### Temporal 42 % preset

The Upscaling tab has a one-click **Temporal 42 %** preset: it selects MSR2 and sets the internal
resolution to 42 %. In a controlled FSR 1 50 % comparison (Complementary, shadows every frame,
512 shadow map) this ran at **149 fps** vs **133 fps** for FSR 1 50 %, with MSR2 at 50 % measuring
**131 fps**. The image is softer than FSR 1 50 %, so this is a performance mode, not a free
quality upgrade. The preset only writes the existing `renderScale.percent`, `msr2.enabled` and
`upscaler` values; you can tune them afterwards.

### FP16 world target

`renderScale.fp16ColorTarget` (default on) allocates the pre-upscale colour attachment as
`RGBA16F` instead of `RGBA8`. Shader packs produce HDR values and tonemap at the end of their own
chain, so with an 8-bit buffer the image is quantised *before* the upscaler sees it - visible as
banding in sky gradients and dark scenes, and as slightly harsher sharpening in the FSR/MSR upscalers.

Measured cost on a 5060 Ti at 960x540: within run-to-run noise (98.6 fps vs 98.9 fps with the full
Complementary config, 275.8 vs 273.2 fps with shadows off), because the buffer is the small,
scaled one. The win is image quality: the upscaler now reads the same HDR range the pack rendered.

## How it works

```
GameRenderer#renderLevel (HEAD)
  plain modes (bilinear/nearest/soft/FSR 1)
                                       -> install a minimal colour+depth target in the real
                                          Minecraft#mainRenderTarget field, bind and clear it
  advanced modes (MSAA/FP16/SSAA/MSR)  -> bind the scaled target and redirect
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
* The upscaler shaders are ordinary Minecraft core shaders
  (`assets/modernopt/shaders/core/*.json`) created through `ShaderInstance`, so they are
  reloaded with F3+T like any resource-pack shader. They are rebuilt lazily when the programs
  vanish after a reload.

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
* MSR uses camera-reconstructed motion vectors. It has no per-object velocities, so fast
  moving entities and particles can ghost briefly before the neighbourhood clamp rejects them.
  Under an Iris shader pack, depth is read from the pack's gbuffer when Iris exposes it; if it
  cannot be read, MSR falls back to rotation-only reprojection for that frame and logs a
  warning once.
* Under a heavy shader pack, MSR cannot make the pack's fixed full-resolution passes cheaper.
  Complementary's shadow map, composite and post chain keep the GPU busy even at 25 % render
  scale; the upscaler only scales the gbuffer/world pass. Reaching a much higher frame rate can
  therefore require lowering the pack's shadow or post settings, not just the render scale.
* The status overlay is hidden while the F3 debug screen is open, because both use the top-left
  corner; F3 already reports the frame rate.

## Testing

Verified in a NeoForge dev client with only **Sodium 0.8.13**, **Iris 1.8.14-beta.1** and
**Complementary Unbound r5.9.3** installed: render scale 50-75 %, bilinear, FSR 1 and MSR,
correct depth (no holes or LODs over the world), HUD at native resolution, first person hand and
nearby entities visible in place under an active shader pack, and no OpenGL errors when the window
is resized or the shader pipeline is rebuilt.

MSR was also checked with shaders off, at 100 % (temporal AA + RCAS at native resolution) and
under Complementary. With the pack active and the world loaded, the test scene at `300,63,300`
measured on an RTX 5060 Ti 16 GB / Ryzen 7 7800X3D at 1920x1080, render distance 12:

| Mode | Frame rate | GPU |
| --- | ---: | ---: |
| Native (mod disabled) | 111 fps | 94 % |
| MSR, 100 % | 94 fps | 96 % |
| MSR, 75 % | 123 fps | 96 % |
| MSR, 50 % | 197-198 fps | 94 % |
| MSR, 25 % | 216 fps | 93 % |

The 50 % result is a real 1.78x over native. The small gain from 50 % to 25 % shows where the
shader pack's fixed cost takes over; it is not an upscaler bug. Native 1:1 crops of the water,
sky and HUD were clean, and an idle camera test measured no visible frame-to-frame shift.

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

Measured in the same world, pack and shader options (Complementary Unbound, `SHADOW_QUALITY=0`,
render distance 16, simulation 5, RTX 5060 Ti):

| mode | FPS |
|---|---|
| world-only (default) | 123 - 140 |
| scale whole window | 138 - 140 |

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
previous map. The held light matrix is translated with the camera so shadows stay attached to the
world; a large camera move or a **sun/time jump** forces an immediate rebuild. Clears that target the shadow framebuffer are skipped while the map is being held,
otherwise they would wipe it. With interval 1 the feature is a no-op.

Measured in the same world with the pack's shadows enabled (render distance 16, simulation 5,
1920x1080, 50 % world):

| interval | FPS | frame |
|---|---|---|
| 1 (every frame) | 100-104 | 9.6-10.0 ms |
| 2 | 140 | 7.2 ms |
| 3 | 149-150 | 6.7 ms |

Screenshots at interval 1 and 3 show the same shadow placement; the remaining pixel differences
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

ModernOPT is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

