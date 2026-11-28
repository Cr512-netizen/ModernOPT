# Benchmark and validation methodology

Every performance number in the README comes from this document. If a result was produced with a
different scene, resolution, stack or ghosting policy, it is not comparable and does not belong in
the README tables.

## Control stack

### `control` (measured)

| Component | Version |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.264 |
| Sodium | 0.8.13+mc1.21.1 |
| Iris | 1.8.14-beta.1+mc1.21.1 |
| Complementary Unbound | r5.9.3 |
| ModernOPT | version under test |

This is the minimum stack in which the render target, Iris pipeline and shadow-hold hooks are
actually exercised. Nothing else is installed.

## Scene

* 1920x1080 window, render distance 16, simulation distance 5 unless a state says otherwise.
* World spawn, standing on the ground, fixed yaw/pitch. The harness teleports to spawn at the start
  of every run, so a previous `!walk` state cannot leak its position into the next run; use
  `-Dmodernopt.bench.keepPosition=true` only for manual inspection.
* Time set to 6000, weather clear, `doDaylightCycle false`, `tick freeze`.
* The camera stays still for throughput states. `!panN` rotates at N degrees per tick and `!walkN`
  moves forward at N/100 blocks per tick for motion states.
* Shadow map size and interval are part of each state's record and are printed next to every result.

## Run

`runClient` is a normal development client: the benchmark harness is disabled unless a `-Pbench*`
option is passed. `runBench` is the explicit benchmark entry point.

```bash
./gradlew runBench -PbenchStates=FSR1-50!shadow2,MSR2-42!shadow2 \
  -PbenchWarmup=60 -PbenchSample=160
```

Throughput runs disable the GPU stage timers by default because the timer queries and their
synchronisation cost 10-20 % FPS on current drivers, which understates the renderer's real frame
rate. Add `-PbenchProfile` to a separate run when the per-stage GPU breakdown is needed; do not
compare its absolute FPS with a throughput run.

The harness focuses the fullscreen window and disables vsync/frame limiting before sampling. If
Windows refuses the focus request, click the window once; sampling is paused while it is inactive so
OS-capped background frames cannot enter the results. Chunk streaming must be stable for 40 ticks
before the first state starts, and screenshots are taken before/after the sample window, never in
the middle of it.

State suffixes used by the harness:

| Suffix | Meaning |
| --- | --- |
| `!shadowX` | Average shadow update interval X (1.0-8.0, 0.5 steps, e.g. `!shadow1.5`). Without it the benchmark forces interval 1. |
| `!conv` | Saves frames 1, 10 and 60 and computes convergence/detail metrics. |
| `!panN` | Rotates the camera N degrees per tick (default 2). |
| `!walkN` | Walks forward N/100 blocks per tick (default 0.04). |
| `!sunset` | Sets the server time to 13000. |
| `!reload` | Closes and rebuilds the mod's shader programs at state start. |
| `!pairs` | Saves four consecutive frames (p40..p43) for flicker inspection and logs the shadow depth-texture hash plus the shadow-fed custom images kept on held frames, so a cleared map or a dropped custom image is visible even when animated foliage or clouds dominate the screenshots. |
| `!mob` | Summons a pig in front of the player for temporal ghosting and flying-entity checks. |
| `!nomv` | Disables MSR2's per-entity motion vectors for an A/B against camera-only reprojection. |
| `!swapmv` | Toggles MSR2 motion vectors at pair frame 40 for a same-run A/B. |
| `!msaaN` | Audit switch: requests MSAA 2/4/8/16. Ignored while an Iris pack is active. |
| `!ssaa` | Audit switch: requests SSAA. Ignored while an Iris pack is active. |
| `!fxaa` | Audit switch: enables FXAA for the state. |
| `!drs` | Audit switch: enables MSR2 dynamic resolution for the state. |
| `!lanczos3` | Uses the wider Lanczos-3 kernel for FSR 1, MSR and MSR2. |
| `!nolanczos` | Disables Lanczos for FSR 1/MSR and inside MSR2. |

The harness writes `run/bench-results.json` with an environment fingerprint (GPU, driver/GL
version, Minecraft, NeoForge, mod, shader pack, render distance, window) and, for every state, the
render scale, upscaler, MSAA/SSAA/FXAA/FP16, MSR2 (Lanczos, motion vectors) and shadow settings,
mean FPS, 1% low, p99 and
worst frame time, shadow drawn/held counts and force reasons, per-stage GPU times when
`-PbenchProfile` is set, and image metrics.

The screenshot frame is excluded from the frame-time statistics: writing a 1080p PNG stalls that
frame for hundreds of milliseconds and would otherwise dominate the 1% low.

## Upscaler quality metrics

Every state's end screenshot (or `f60` for `!conv`) is analysed after the run. Since 0.7.6
whole-window is always on and the screenshot is taken from the native-resolution upscaled output,
not from the small intermediate frame. The default reference is the `BILINEAR-100!shadow2`
screenshot from the same run, then `BILINEAR-100`, then `OFF-100`; `-PbenchReference=<png>`
overrides it. Put the reference state first:

```bash
./gradlew runBench -PbenchStates=BILINEAR-100!shadow2,BILINEAR-50!shadow2,\
NEAREST-50!shadow2,SOFT-50!shadow2,FSR1-50!shadow2,MSR-50!shadow2,\
MSR2-42!shadow2,MSR2-50!shadow2,MSR2-67!shadow2 \
  -PbenchWarmup=30 -PbenchSample=40
```

The metrics are computed on a central world ROI (`x = 8..92 %`, `y = 32..82 %`) so the sky,
status overlay, hotbar and hand do not dominate the result:

| Key | Meaning |
| --- | --- |
| `sharpness` | Mean central-difference luma gradient magnitude, 0..1. |
| `highFrequency` | Mean absolute difference from a 3x3 box blur, 0..1. |
| `laplacian` | Mean absolute 4-neighbour Laplacian, 0..1. |
| `contrast` | Luma standard deviation. |
| `qualityMae`, `qualityRmse` | Error against the native reference, 0..1. |
| `psnr` | PSNR against the reference, dB. |
| `ssim` | Mean 8x8-window SSIM against the reference, 0..1. |
| `chromaMae` | Mean absolute YCoCg chroma error against the reference, 0..1. Lower is better; catches colour fringes that a luma-only SSIM misses. |
| `overshoot` | Fraction of ROI pixels more than 6/255 outside the 3x3 native luma range. Lower is better; catches halos invented by sharpening or Lanczos ringing. |
| `sharpnessRatio`, `highFrequencyRatio` | Relative to the reference. |

The log prints a `[BENCH][QUALITY]` line per state and a ranking by SSIM; `bench-results.json`
contains the same data under `quality.ranking` and each state's `image` object. `qualityComparable`
is false for motion, sunset and third-person states, where the native reference is not a valid
comparison. Whole-window is the only presentation mode now, so `!wws` no longer affects
comparability; it is kept as a no-op suffix for old state lists.

Sharpness alone is not quality: Nearest-neighbour can have a high gradient while looking blocky.
Use SSIM/MAE together with the sharpness ratio: a good upscaler keeps the ratio near 1 without
losing much SSIM. `chromaMae` and `overshoot` separate a genuinely sharper reconstruction from one
that only adds colour fringes and halos.

Two audit-only suffixes isolate the stages when tuning:

* `!sharpN` sets both the FSR RCAS and MSR2 sharpness to `N` (0..100). Use `!sharp0` to compare
  reconstruction filters without the sharpening stage.
* `!histNN` sets the MSR2 history weight to `NN` percent (50..95). It is useful for checking
  temporal convergence separately from the spatial filter.

### Baselines

```bash
./gradlew runBench -PbenchBaseline=bench-baseline.json \
  -PbenchTolerance=0.03 -PbenchStates=...
```

`bench-regression.txt` is written when mean FPS or 1% low falls more than the tolerance below the
baseline. CI should treat a non-empty file as a failure. Baseline files are only valid on the same
GPU, driver, pack and fingerprint; the report prints a warning when they differ.

## Image checks

`!conv` measures:

* `maeFrame1To60`, `maeFrame10To60` - mean absolute luma error between presented frames. A static
  camera with correct jitter/history must converge, so these should be small and dominated by
  animated clouds.
* `detail` - mean horizontal luma gradient. Compare with a native-100 reference of the same scene;
  a large drop means the reconstruction is blurring real detail.
* `referenceMae` and `referenceDetail` when `-PbenchReference=<png>` is supplied.

The standalone comparator can be run on any pair of frames:

```powershell
powershell -File tools/compare-images.ps1 -Reference run/screenshots/native.png `
  -Candidate run/screenshots/upscaled.png
```

It reports luma MAE for the full frame and for the region below the top 35 %, plus mean horizontal
luma gradient and its ratio to the reference. The below-sky figure excludes animated clouds, which
otherwise dominate the error. A detail ratio near or above 1 means the reconstruction did not
throw away high-frequency content.

For motion, `!pan2!conv` and `!walk4!conv` expose history rejection and ghosting. Inspect the saved
frames for trails on mobs, flowers and contraptions; the metrics alone cannot prove the absence of
ghosting.

## GPU floor

`nvidia-smi --query-gpu=utilization.gpu,clocks.sm,power.draw --format=csv -l 1` is sampled during a
separate run and is not run concurrently with a timing run because the sampler itself adds
hitches. If utilisation stays below 90% while FPS is capped, the render thread is the bottleneck and
further GPU passes will not raise the frame rate.

## Shader validation

```bash
./gradlew validateShaders -PrequireShaderTools=true
```

Runs `glslangValidator` on every `*.vsh`, `*.fsh` and `*.comp`, compiles the compute pass to
OpenGL SPIR-V and runs `spirv-val --target-env opengl4.3` on it. CI installs `glslang-tools` and
`spirv-tools` and sets `requireShaderTools=true`.
