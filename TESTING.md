# Benchmark and validation methodology

Every performance number in the README comes from this document. If a result was produced with a
different scene, resolution, stack or ghosting policy, it is not comparable and does not belong in
the README tables.

## Named stacks

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

### `representative` (pinned, results pending)

The control stack plus the common performance mods players run together:

| Component | Version |
| --- | --- |
| performance mod | mc1.21.1-0.15.4-neoforge |
| performance mod | 7.0.3-neoforge |
| performance mod | 5.27.27+mc1.21.1 |
| culling mod | 1.12.1 |
| culling mod | 1.0.11 |
| performance mod | 1.6.15+1.21.1-neoforge |

Every feature that overlaps with one of these mods must be measured twice: once with ModernOPT and
the overlapping mod enabled, and once with the mod disabled, in the same run. Otherwise the result
credits ModernOPT with work another mod did.

## Scene

* 1920x1080 window, render distance 16, simulation distance 5 unless a state says otherwise.
* World spawn, standing on the ground, fixed yaw/pitch.
* Time set to 6000, weather clear, `doDaylightCycle false`, `tick freeze`.
* The camera stays still for throughput states. `!panN` rotates at N degrees per tick and `!walkN`
  moves forward at N/100 blocks per tick for motion states.
* Shadow map size and interval are part of the fingerprint and are printed next to every result.

## Run

```bash
./gradlew runClient -PbenchStates=FSR1-50!shadow2,MSR2-42!shadow2 \
  -PbenchWarmup=60 -PbenchSample=120
```

State suffixes used by the harness:

| Suffix | Meaning |
| --- | --- |
| `!shadowN` | Shadow update interval N (1-8). Without it the benchmark forces interval 1. |
| `!conv` | Saves frames 1, 10 and 60 and computes convergence/detail metrics. |
| `!panN` | Rotates the camera N degrees per tick (default 2). |
| `!walkN` | Walks forward N/100 blocks per tick (default 0.04). |
| `!sunset` | Sets the server time to 13000. |
| `!lanczos3` | Uses the wider Lanczos-3 kernel. |
| `!nolanczos` | Disables Lanczos reconstruction. |

The harness writes `run/bench-results.json` with a fingerprint (GPU, driver/GL version, Minecraft,
NeoForge, mod, shader pack, render distance, window, scale, upscaler, MSAA/SSAA/FXAA/FP16, MSR2,
shadow map and interval), per-state mean FPS, 1% low, p99 and worst frame time, shadow drawn/held
counts and force reasons, per-stage GPU times, and image metrics.

The screenshot frame is excluded from the frame-time statistics: writing a 1080p PNG stalls that
frame for hundreds of milliseconds and would otherwise dominate the 1% low.

### Baselines

```bash
./gradlew runClient -PbenchBaseline=bench-baseline.json \
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
