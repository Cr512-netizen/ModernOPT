# ModernOPT

Render scale, upscalers and anti-aliasing for **Minecraft 1.21.1 / NeoForge**.

A small, self-contained alternative to OptiFine-style render scaling: the world is rendered into
an off-screen target at a lower (or higher) internal resolution, and a reconstruction filter
brings it back to the window resolution. The HUD, chat and every screen stay at native
resolution.

## Features

| Option | Values | What it does |
| --- | --- | --- |
| Render scale | 25 - 100 % | Internal world resolution. 75 % means roughly 56 % of the pixels. |
| Upscaler | `Bilinear`, `Nearest`, `Soft scale`, `FSR 1`, `FSR 2` | Filter used to present the internal image. `FSR 2` adds a temporal accumulation pass. |
| SoftScale softness | 0 - 100 % | Blur radius of the 9-tap tent filter (`Soft scale`). |
| FSR sharpness | 0 - 100 % | RCAS strength applied after the FSR 1 EASU or FSR 2 temporal upscale. |
| MSAA | Off, 2x, 4x, 8x, 16x | Multisampling for the world pass (hardware, resolved before the upscale). |
| SSAA | On / Off | Renders the world at 2x resolution and downsamples with a 2x2 box filter. |
| FXAA | On / Off | Post-process edge smoothing on the final native-resolution image. |
| Status overlay | On / Off | FPS, internal resolution, active filter, MSAA level. |

Anti-aliasing order is always *world -> SSAA downsample -> reconstruction filter -> FXAA*, so the
options compose instead of fighting each other.

### FSR 2

`FSR 2` is a temporal upscaler in the same shape as AMD's FSR 2: the world is rendered with a
sub-pixel Halton jitter, EASU brings the current frame to output resolution, a temporal pass
reprojects the previous frame with the camera matrices and accumulates it in an RGBA16F history
buffer, and RCAS sharpens the result (FXAA can follow). A depth test rejects disoccluded pixels,
and a 3x3 YCoCg neighbourhood clamp plus a reactive mask suppress ghosting.

It is not a byte-for-byte port of AMD's FSR 2 shaders. Motion vectors are reconstructed from the
camera only, so moving entities, particles and the hand rely on the clamp/reactive rejection
instead of per-object velocities; fast objects can leave a short trail. The first frame after a
world load (FOV still 0, degenerate projection) is skipped and the history is reset.

FSR 2 runs at 100 % too, where it acts as temporal anti-aliasing. SSAA has priority: enabling
SSAA turns FSR 2 off. With an Iris shader pack active, ModernOPT disables its own projection
jitter and lets the pack's temporal filter drive sub-pixel jitter; FSR 2 still reprojects the
pack's output and upscales it. MSAA is also forced off in FSR 2 mode and that is logged once.

## How it works

```
GameRenderer#renderLevel (HEAD)          -> bind the scaled target, redirect
                                            Minecraft#getMainRenderTarget to it
   world, entities, particles, weather   -> drawn into the internal target
GameRenderer#render before Gui#render    -> drop the redirect, resolve MSAA,
                                            run upscaler (+ optional FXAA) into the
                                            real main target, bind it again
Gui#render                               -> native resolution, untouched
```

Key points:

* `Minecraft#getMainRenderTarget()` is the only thing that is redirected, so vanilla code paths
  and mods that ask the game "where do I draw" keep working (Sodium chunk rendering, Distant
  Horizons, weather, post effects).
* The scaled target is a real `RenderTarget` (colour + depth texture), so anything that samples
  the depth buffer still finds one.
* MSAA uses a separate multisampled framebuffer and resolves only the colour buffer; the depth
  buffer of the single-sampled target is what other code reads.
* The upscaler shaders are ordinary Minecraft core shaders
  (`assets/modernopt/shaders/core/*.json`) created through `ShaderInstance`, so they are
  reloaded with F3+T like any resource-pack shader. They are rebuilt lazily when the programs
  vanish after a reload.

## Compatibility

* **Sodium** - no rendering conflict by design (only `getMainRenderTarget()` is redirected). The
  Sodium video settings get a **ModernOPT** page that opens the full options screen, plus a
  native **Settings** page with the common options. This uses Sodium 0.8.13's config API and its
  `sodium:config_api_user` mod property, the same mechanism Iris uses. Sodium is a compile-only
  dependency: its classes are only touched by `ModernOptSodiumConfig`, which Sodium itself loads,
  so the mod still starts normally when Sodium is absent.
* **Iris** - supported. Iris sizes its gbuffer and depth targets from
  `Minecraft#getMainRenderTarget()`, so the same redirect scales the whole shader pipeline, not
  just the vanilla world pass. While a shader pack is active MSAA is forced off (the pack owns
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
* **Distant Horizons** - rendered through the same redirected target, so LODs scale together with
  the rest of the world.

## Limitations (honest list)

* MSAA is disabled while an Iris shader pack is active; the pack brings its own antialiasing.
* A shader pack owns its lighting pipeline, so effects it computes at internal resolution
  (shadows, SSAO, reflections) are also at internal resolution. That is the point of the feature,
  but it means very low scales soften those effects too.
* The upscalers are presentation filters. They cannot recover detail that was never rendered,
  which is why FSR 1 looks best at 67-85 % scale.
* FSR 2 uses camera-reconstructed motion vectors. It has no per-object velocities, so fast
  moving entities and particles can ghost briefly before the neighbourhood clamp rejects them.
  Under an Iris shader pack, depth is read from the pack's gbuffer when Iris exposes it; if it
  cannot be read, FSR 2 falls back to rotation-only reprojection for that frame and logs a
  warning once.
* Under a heavy shader pack, FSR 2 cannot make the pack's fixed full-resolution passes cheaper.
  Complementary's shadow map, composite and post chain keep the GPU busy even at 25 % render
  scale; the upscaler only scales the gbuffer/world pass. Reaching a much higher frame rate can
  therefore require lowering the pack's shadow or post settings, not just the render scale.
* The status overlay is hidden while the F3 debug screen is open, because both use the top-left
  corner; F3 already reports the frame rate.

## Testing

Verified in a NeoForge dev client with only **Sodium 0.8.13**, **Iris 1.8.14-beta.1** and
**Complementary Unbound r5.9.3** installed: render scale 50-75 %, bilinear, FSR 1 and FSR 2,
correct depth (no holes or LODs over the world), HUD at native resolution, first person hand and
nearby entities visible in place under an active shader pack, and no OpenGL errors when the window
is resized or the shader pipeline is rebuilt.

FSR 2 was also checked with shaders off, at 100 % (temporal AA + RCAS at native resolution) and
under Complementary. With the pack active and the world loaded, the test scene at `300,63,300`
measured on an RTX 5060 Ti 16 GB / Ryzen 7 7800X3D at 1920x1080, render distance 12:

| Mode | Frame rate | GPU |
| --- | ---: | ---: |
| Native (mod disabled) | 111 fps | 94 % |
| FSR 2, 100 % | 94 fps | 96 % |
| FSR 2, 75 % | 123 fps | 96 % |
| FSR 2, 50 % | 197-198 fps | 94 % |
| FSR 2, 25 % | 216 fps | 93 % |

The 50 % result is a real 1.78x over native. The small gain from 50 % to 25 % shows where the
shader pack's fixed cost takes over; it is not an upscaler bug. Native 1:1 crops of the water,
sky and HUD were clean, and an idle camera test measured no visible frame-to-frame shift.

## Configuration

`config/modernopt-client.toml` (created on first launch), or the in-game screen:

* `Options -> ModernOPT` on NeoForge's mod list, or
* `Video Settings -> ModernOPT` in Sodium (the full screen or the native `Settings` page), or
* the `F10` keybind (in game).

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
* The FSR 2 temporal upscaler is an original implementation inspired by AMD's
  [FidelityFX Super Resolution 2](https://github.com/GPUOpen-Effects/FidelityFX-FSR2) design
  (jitter, reprojection, history clamping, RCAS). It shares no code with the FSR 2 reference.
* FXAA follows Timothy Lottes' original luma-based algorithm.
