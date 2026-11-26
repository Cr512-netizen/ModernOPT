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
| Upscaler | `Bilinear`, `Nearest`, `Soft scale`, `FSR 1`, `MSR`, `MSR2` | Filter used to present the internal image. The selection is authoritative: Bilinear, Nearest and Soft always use their own kernel. `MSR` adds a temporal accumulation pass. `MSR2` is the newest upscaler, built on camera reconstruction plus per-entity motion vectors, with an output-resolution RGBA16F history and a Lanczos-2 base (EASU when the shared toggle is off). |
| Lanczos reconstruction | On / Off | Replaces EASU with Lanczos-2 for FSR 1 and MSR. MSR2 uses Lanczos-2 when this is on (the default) and EASU when it is off. Bilinear, Nearest and Soft keep their own kernel. `Lanczos-3` switches the wider kernel. |
| Entity motion vectors | On / Off | Per-entity screen-space vectors for MSR2. Moving mobs are reprojected with their own motion instead of relying only on the reactive mask. Disable for an A/B against camera-only reprojection. |
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
sub-pixel R2 jitter, EASU brings the current frame to output resolution, a temporal pass
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

`MSR2` is the newest upscaler in ModernOPT. It uses camera reconstruction for the static world and
per-entity screen-space motion vectors for moving entities, with an output-resolution history and
an RCAS sharpen pass.

Key properties:
- History is **RGBA16F colour + R16_UINT depth**, allocated at the **output resolution**. The
  temporal pass therefore keeps the detail it accumulates instead of throwing it away before the
  final upscale. At 1080p the colour pair is about 32 MiB and the depth pair about 8 MiB, so the
  ping-pong history costs roughly 40 MiB.
- The temporal pass runs as a compute shader on GL 4.3+ and dispatches one thread per output
  pixel; the final pass is RCAS, or a plain copy when sharpening is off.
- The temporal path runs when the upscale factor is above 1.11x (below about 90 % scale). At
  90-99 % MSR2 uses the spatial MSR shader (edge-aware + CAS); at 100 % with no SSAA the
  offscreen pipeline is bypassed and the frame is native. Neither path accumulates history.
- FXAA is not applied on the MSR2 path; RCAS is the only post filter. Use FSR 1 or MSR if FXAA is
  required.
- MSR2 applies an R2 sub-pixel jitter and samples the history on the unjittered output grid, so
  accumulated samples do not drift by the difference between jitter phases. History that moves more
  than 15 % of the screen in one frame is rejected.
- **Per-entity motion vectors** are collected during the world pass: the projection of each
  entity's bounding box and screen centre is recorded, and a source-resolution RGBA16F target
  stores `previousUv - currentUv` plus a coverage mask. The temporal shader uses the entity vector
  where the coverage and depth test pass and camera reprojection everywhere else. The vector pass
  is skipped entirely when no tracked entity has moved in world space: a world-static entity is
  already reprojected exactly by the camera matrices. Small items, arrows, XP orbs and particles
  are deliberately left to the reactive mask instead of applying a box-sized velocity to the
  ground behind them.
- The low-resolution current frame is reconstructed with **Lanczos-2** when the shared Lanczos
  toggle is on (the default), or with **EASU** when it is off. The EASU prepass shifts its source
  sampling by the frame's sub-pixel jitter, so its output is already aligned to the unjittered
  history grid. FSR 1 and MSR use the same toggle in their spatial pass; Bilinear, Nearest and
  Soft keep their own kernels. The Lanczos kernel weights come from a 65-phase polyphase table in
  a small R32F texture; when scene depth is unavailable the per-tap depth fetch is skipped, not
  just its weight.
- The temporal acceptance box is a tight 3x3 neighbourhood of the current sample for both
  reconstruction paths. It intentionally does **not** use the wide Lanczos footprint: that box
  grew with the upscale ratio (twelve output pixels at 50 %) and let a moving entity keep its old
  colour for many frames, which showed up as a second, flying copy of the mob.
- Moving entities and particles are handled with per-entity vectors where available and a
  reactive history mask everywhere else: when the clamped history differs from the current
  neighbourhood, the history contribution drops smoothly. This rejects the old colour before it
  can remain behind as a second, flying copy of a mob or the player's body.
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
run below its mean was about 8 % above FSR 1 + Lanczos-2 at 50 %, but its 1% low was lower
(122.2 vs 129.0 fps) and the image is softer; the output-resolution temporal pass costs most of
what the lower world resolution saves. The preset only writes the existing `renderScale.percent`
and `upscaler` values, so it is easy to A/B in your own scene.

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

## Limitations (honest list)

* MSAA is disabled while an Iris shader pack is active; the pack brings its own antialiasing.
* A shader pack owns its lighting pipeline, so effects it computes at internal resolution
  (shadows, SSAO, reflections) are also at internal resolution. That is the point of the feature,
  but it means very low scales soften those effects too.
* The upscalers are presentation filters. They cannot recover detail that was never rendered,
  which is why FSR 1 looks best at 67-85 % scale.
* MSR uses camera-reconstructed motion vectors only. MSR2 adds per-entity screen-space boxes for
  moving mobs and players; particles, translucent entities and per-vertex deformation still have no
  per-pixel vectors, so their edges can soften. The boxes are depth-gated but approximate; small
  items, arrows and XP orbs are deliberately left to the reactive mask. FSR 3 frame generation is
  not implemented.
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

The methodology and the state suffixes are in [TESTING.md](TESTING.md). `runClient` is a normal dev
client; `runBench` is the explicit benchmark entry point. All README numbers below come from the
same scene and the same harness:
RTX 5060 Ti 16 GB / Ryzen 7 7800X3D, 1920x1080, Complementary Unbound r5.9.3, render distance 16,
frozen time and weather, static camera, 512 shadow map, 60-tick warmup and 160-tick sample. The
control stack is Minecraft 1.21.1, NeoForge 21.1.264, Sodium 0.8.13 and Iris 1.8.14-beta.1.

| Mode | Mean FPS | 1% low | Shadow interval | Held share |
| --- | ---: | ---: | ---: | ---: |
| Native Iris (mod disabled, 100 %) | 131.5 | 114.8 | - | - |
| ModernOPT passthrough, 100 % | 147.0 | 133.2 | 2 | 50 % |
| FSR 1 + Lanczos-2, 50 % | 143.6 | 129.2 | 2 | 50 % |
| FSR 1 + Lanczos-2, 50 % | 126.7 | 114.8 | 1 | 0 % |
| FSR 1 EASU (Lanczos off), 50 % | 143.5 | 129.4 | 2 | 50 % |
| MSR2, 50 % | 135.4 | 121.9 | 2 | 50 % |
| MSR2, 42 % | 150.6 | 135.8 | 2 | 50 % |

Throughput runs disable the GPU stage timers, because their queries and readback add about
1.2-1.5 ms per frame (10-20 %) on this driver. That overhead is why earlier tables reported
130-135 FPS at 50 % instead of 143-145; `-PbenchProfile` enables the timers for a separate
breakdown and must not be compared with these totals.

`ModernOPT passthrough, 100 %` is scale 100 with no AA: the offscreen pipeline is bypassed and the
world renders into the main target, but shadow holding still applies. It is 12 % above the
mod-disabled native row because that row has no shadow holding and none of the mod's
optimisations.

The MSR2 rows above are **true temporal runs**. The compute program is compiled when MSR2 is
selected at runtime; before 0.7.0 a runtime switch could silently keep the spatial fallback, which
made the old 42 % figure look faster but did not accumulate history.

`Held share` is `held / (drawn + held)` for the same measurement window as the frame times; the
interval is printed next to it because 50 % is the correct value for interval 2, not a defect.
The 1% low is the mean of the slowest 1 % of frames after excluding the screenshot frame. Sampling
pauses while the window is inactive; screenshots are taken outside the sample window, so OS focus
drops and PNG writes cannot enter the numbers. The player is teleported to the same spawn pose at
the start of every run. Absolute FPS depends on GPU state; compare rows from this one run, not
numbers from older releases.

MSR2 50 % is about 6 % below FSR 1 + Lanczos-2; the true temporal pass costs more than the old
spatial fallback. MSR2 42 % is 11 % above MSR2 50 % and about 5 % above FSR 1 at 50 %, with a
higher 1% low in this run (135.8 vs 121.9); the image is softer, so it remains an optional
experimental preset, not the default. Lanczos-2 and EASU on FSR 1 are within noise of each other
at 50 %; the choice is visual. The shadow interval change from 1 to 2 is worth about 13 % mean FPS
here with the block-boundary rebuild rule.

### Frame cost

The table below is measured with `-PbenchProfile` and scaled proportionally to the throughput
frame time, because the timers themselves add about 1 ms. The split, not the absolute number, is
the useful part.

| Stage per frame (ms) | FSR 1, 50 % | MSR2, 50 % | MSR2, 42 % |
| --- | ---: | ---: | ---: |
| Iris/Sodium world pass | 6.71 | 6.89 | 6.16 |
| – terrain | 3.58 | 3.51 | 3.00 |
| – shadow map, amortised | 1.15 | 1.21 | 1.20 |
| – Iris composite, final and sky | 0.63 | 0.66 | 0.55 |
| – entities, translucency and other world work | 1.34 | 1.51 | 1.41 |
| Upscale (FSR 1 or MSR2 temporal + RCAS) | 0.18 | 0.31 | 0.31 |
| Present and CPU overhead | 0.07 | 0.14 | 0.13 |
| **Total** | **6.96** | **7.34** | **6.60** |

MSR2's temporal compute plus RCAS is 0.31 ms, about 5 % of the 42 % frame; FSR 1's spatial pass is
0.18 ms. The motion-vector pass is skipped when no tracked entity moves in world space, so static
scenes pay only for the temporal+RCAS chain. The EASU path (Lanczos off) measured about 153.4 FPS
against 151.7 FPS for Lanczos-2 at 42 % in the same run, roughly a 1 % difference. The frame is
still the world pass. Lowering the scale from 50 to 42 % cuts terrain from
3.51 to 3.00 ms, but the shadow map and the non-pixel part of the world stay nearly constant, which
is why the frame falls by 10 % rather than the 29 % fewer pixels.

A spot check with `nvidia-smi` during these runs reported 100 % GPU utilisation at 2.7 GHz and
about 110 W, so the 1080p scene is GPU-bound. CPU-side draw submission is not the limiter at this
resolution; internal pixels, the shadow map and the pack's full-resolution passes are. The sampler
itself adds hitches, so it is never run during a timing run.

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
* `F9` cycles the upscaler in game and shows the new mode in the action bar. Use it when a menu
  pauses the world or to compare filters without leaving the game.

The full screen is split into **Upscaling**, **Anti-aliasing** and **Shadows** tabs. Closing the
screen rebuilds ModernOPT's shader programs from the current resource tree and resets the temporal
histories, so an upscaler or Lanczos change applies without F3+T. A shadow-map resolution change
also reloads the Iris pack once. Every effective upscaler change is logged as
`[ModernOPT] upscaler old -> new`; if that line does not appear when switching in a menu, the menu
did not apply the option and `F9` can be used to verify the renderer directly.

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

`shadows.updateInterval` (1.0-8.0 in 0.5 steps, default 2.0) rebuilds the shadow map every N frames
on average instead of every frame. Fractional values alternate the gaps: 1.5 produces two draws and
one skip (draw, draw, skip), while 2.5 alternates a two-frame and a one-frame gap. Use 1.5 when the
two-frame cadence is visible: it keeps the longest gap at one held frame while still skipping one
shadow pass in three. The frames in between skip the whole second world pass from the sun and keep
sampling the previous map. The shadow lookup matrix stays anchored to the frame that drew the map: Iris rebuilds
it from the drawn camera and translates it by the current camera delta after the shadow-map snap.
The drawn frame's sun angle is kept for the lookup as well, so a moving sun cannot rotate the
shadow pattern between rebuilds. A camera leaving the integer block it occupied when the map was
drawn forces an immediate rebuild.
That is the only extra redraw, so a held sequence keeps a regular N-frame cadence; normal daylight
motion and moving entities do not insert extra redraws between them. The first-person player is not
drawn into the shadow map.

Iris clears the depth-source framebuffer directly and clears every custom image at the start of
each level frame, before the shadow pass. The depth clear is suppressed through the level-start
window, with the resolved `GlFramebuffer#getId` as a fallback; if neither is available, the hold
disables itself instead of flickering. While Iris compiles the shadow programs, the hold records
which custom images those programs declare (for example a voxel or reflection image). Only those
images are kept on a reused frame; images the shadow pass does not write are still cleared
normally, which avoids stale contents and black patches. The shadow composite chain is re-run on
held frames when at least one such image exists, so packs that derive composite outputs from the
shadow map stay current. If Iris stopped tagging its shadow clear passes, the hold also disables
itself with a single warning.

Measured in the same scene as the table above (FSR 1, 50 %, 512 shadow map):

| Interval | Mean FPS | 1% low | Held | Shadow amortized |
| ---: | ---: | ---: | ---: | ---: |
| 1 (every frame) | 130.2 | 115.5 | 0 % | 2.44 ms |
| 2 | 143.3 | 129.0 | 50 % | 1.20 ms |

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

