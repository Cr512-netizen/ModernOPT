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
| Upscaler | `Bilinear`, `Nearest`, `Soft scale`, `FSR 1` | Filter used to present the internal image. |
| SoftScale softness | 0 - 100 % | Blur radius of the 9-tap tent filter (`Soft scale`). |
| FSR 1 sharpness | 0 - 100 % | RCAS strength applied after the EASU upscale. |
| MSAA | Off, 2x, 4x, 8x, 16x | Multisampling for the world pass (hardware, resolved before the upscale). |
| SSAA | On / Off | Renders the world at 2x resolution and downsamples with a 2x2 box filter. |
| FXAA | On / Off | Post-process edge smoothing on the final native-resolution image. |
| Status overlay | On / Off | FPS, internal resolution, active filter, MSAA level. |

Anti-aliasing order is always *world -> SSAA downsample -> reconstruction filter -> FXAA*, so the
options compose instead of fighting each other.

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
  options screen gets a `ModernOPT` button via a `@Pseudo` mixin, so Sodium is never a compile
  dependency and its absence is harmless.
* **Iris** - while a shader pack is active ModernOPT deliberately keeps its hands off: Iris owns
  the gbuffer/depth pipeline and interposing a rescaled or multisampled target on top of it
  produces broken shadows. Detection is reflective, so Iris is optional. With no shader pack
  selected the mod works normally.
* **Fabulous graphics** - MSAA is skipped (it needs a single-sampled depth buffer); render
  scaling itself still works.
* **Distant Horizons** - rendered through the same redirected target, so LODs scale together with
  the rest of the world.

## Limitations (honest list)

* Everything above about Iris/Fabulous is a deliberate safety decision, not a tested
  combination: this build has **not** been run inside a real client session with Sodium and Iris
  installed. Logic is verified by compilation only.
* MSAA resolves colour only; a shader pack or mod that needs multisampled depth will not get it.
* The upscalers are presentation filters. They cannot recover detail that was never rendered,
  which is why FSR 1 looks best at 67-85 % scale.

## Configuration

`config/modernopt-client.toml` (created on first launch), or the in-game screen:

* `Options -> ModernOPT` on NeoForge's mod list, or
* the Sodium video settings page button, or
* the `F10` keybind (in game).

## Building

Requires JDK 21.

```bash
gradle build
```

The jar lands in `build/libs/modernopt-<version>.jar`. The included GitHub Actions workflow does
exactly this on every push and uploads the jar as an artifact.

## Credits

* FSR 1 EASU and RCAS are a port of AMD's
  [FidelityFX Super Resolution 1](https://github.com/GPUOpen-Effects/FidelityFX-FSR) reference
  implementation (MIT). The reference fetches 2x2 quads with `gather4`; this port fetches each tap
  individually, which is equivalent on any GL 3.2+ driver.
* FXAA follows Timothy Lottes' original luma-based algorithm.
