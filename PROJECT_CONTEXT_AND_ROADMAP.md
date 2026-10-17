# ModernOPT — project context and long-term roadmap

Maintainer/future-assistant handoff. Read this before changing rendering code or choosing the next task.

Last reviewed: 2026-10-16
Repository: https://github.com/Cr512-netizen/ModernOPT
Source version at review: 0.0.39 on draft branch `perf/fsr1-fp16` / PR #6; `main` remains at 0.0.37 until the draft PR is reviewed and merged.
Default branch: main

## 1. Mission

ModernOPT is an experimental client-side rendering and image reconstruction mod for Minecraft Java Edition 1.21.1 on NeoForge. Its purpose is to let players trade internal world-render resolution for performance while retaining native-resolution HUD/GUI, and to provide selectable spatial reconstruction/anti-aliasing options plus useful performance diagnostics.

ModernOPT should complement—not replace—Sodium's terrain renderer or Iris' shader-pack renderer. It must fail safely when a render path is unsupported, preserve the game's rendering state, and never sacrifice frame pacing for a higher FPS counter.

### Product priorities

1. Stability, correct frame pacing, and a reliable master disable switch.
2. Correct and predictable render-scale behaviour.
3. Honest measurement of performance and visual quality.
4. Robust compatibility with supported Minecraft/NeoForge/Sodium/Iris combinations.
5. Efficient post-processing and useful upscaling modes.
6. Only after the core is stable: more advanced reconstruction/anti-aliasing features.
7. Frame generation is a distant, separate research project—not part of current core work.

## 2. Fixed platform and constraints

- Minecraft Java Edition 1.21.1.
- Java 21.
- NeoForge 21.1.x; the current repository build property is 21.1.259.
- Intended integration target includes Sodium 0.8.13+ and Iris 1.8.14-beta.1 / 1.8.14-era as previously specified by the maintainer. Exact support must only be claimed after runtime testing.
- Example shader pack used by the maintainer: Complementary Unbound.
- Aim for broad compatibility with other client mods, including Create/Flywheel, without claiming compatibility without a reproducible test.
- Sodium is optional for the standalone config screen; do not accidentally make it a hard dependency.
- Settings and user-facing documentation should be available in English and Russian.
- The maintainer decided to leave compute/mesh shaders aside; do not add them as a roadmap task merely because they were discussed.
- Do not start Frame Generation until explicitly re-prioritized. Previous FG work was associated with severe perceived stutter/latency despite high displayed FPS; the maintainer wanted it deferred until the upscaling/rendering core is stable.
- Shadow-frame skipping is experimental and must operate at the whole `IrisRenderingPipeline.renderShadows` call boundary, including Iris PREPARE, never by cancelling only `ShadowRenderer.renderShadows`. Preserve the last shadow map together with the matrices that produced it; fail open if Iris fields/settings cannot be inspected. It remains disabled by default. Iris shadow-resolution scaling is separate, optional, and defaults off.
- FXAA should not disable the entire mod when a shader pack is active. Skip only the incompatible effect/pass where needed.
- Never promise a performance gain before repeatable before/after measurements.

## 3. Current repository state (draft PR branch 0.0.39; main 0.0.37)

The project is alpha/experimental. Earlier 0.0.37 and draft-branch builds completed successfully. The current 0.0.39 diagnostics build is being checked by CI; even a successful build verifies compilation and packaging, not in-game behaviour. Full runtime validation across Iris, Sodium, shader packs, and other mods has not been established.

### Existing code areas

- ModernOPT.java: mod bootstrap, client config registration, standalone NeoForge Mods-menu config screen, FSR shader registration.
- config/ModernOptConfig.java: master switch; render scale and upscaler mode; SSAA/MSAA/FXAA; dynamic-resolution bounds/target; overlay/logging/benchmark settings; particle culling; optional Iris shadow-resolution settings.
- client/ModernOptConfigScreen.java: standalone settings pages and controls.
- client/SodiumConfigEntryPoint.java: settings integration into Sodium's options UI when a compatible Sodium Config API is present.
- client/PerformanceOverlay.java: performance HUD, render-target information, frame-time statistics, and non-invasive advice.
- client/BenchmarkRecorder.java: CSV benchmark capture under config/modernopt-benchmarks/.
- client/PerformanceAdvisor.java: conservative hints based on available measurements; it must never change settings automatically.
- render/RenderScaleController.java: bounded dynamic render-scale controller; verify its actual hook/integration and in-game behaviour before treating dynamic resolution as complete.
- render/RenderScalePhase.java: records the temporary scale applied during world rendering.
- render/RenderTargetLifecycle.java: synchronizes selected world auxiliary targets and post chains to world resolution.
- render/RenderDiagnostics.java: CPU wall-clock timings plus asynchronous OpenGL timestamp queries for GPU world/post intervals. GPU results are read only after availability is reported; CPU-only mode remains if timestamps are unsupported. It also tracks main-target restoration and throttled Iris RenderTarget field inspection.
- render/Fsr1Renderer.java: shader registration and post-processing for FSR1-related passes and simple reconstruction modes; reuses an intermediate target for sequential FXAA processing.
- render/MultisampleRenderTarget.java: multisample target implementation.
- mixin/GameRendererMixin.java: critical render interception and state restoration; highest-risk area for regressions.
- Other mixins/accessors cover the renderer, render target, window dimensions, particles, screens, and Iris shadow settings.
- Shader assets currently include FSR1 EASU/RCAS variants and bilinear, nearest-neighbour, SoftScale, FXAA, and SSAA downsample passes.
- THIRD_PARTY_NOTICES.md records FSR1 and RenderScale attribution/licensing. Preserve and update it if third-party code or assets change.

### Current user-visible feature set documented in README

- Internal world render scale from 25% to 100%.
- Spatial reconstruction modes: FSR1 (EASU + RCAS), bilinear, nearest-neighbour, SoftScale.
- SSAA: 2x width and height followed by downsampling; expensive by design and takes priority over render scale/MSAA in the current code.
- MSAA: Off, 2x, 4x, 8x, 16x, with a retry at 8x if 16x allocation fails.
- FXAA post-processing.
- Intended native-resolution HUD/GUI.
- Optional particle draw-distance culling (draw only; particles continue ticking).
- Configurable performance HUD and text log.
- CSV benchmark with frame intervals, FPS, heap usage, process/system CPU, native/world target dimensions, effective scale, shader-pack state, CPU/GPU world/post timing, selected upscaler path, auxiliary/Iris target sizes, and main-target restoration status.
- Conservative troubleshooting hints; no automatic setting changes.
- Master switch; currently defaults off because the rendering pipeline remains experimental.
- Iris shadow-frame pacing is an optional experimental whole-pipeline cache path and is disabled by default. Runtime compatibility and visual correctness still require in-game validation.

Do not describe FSR2, TAA, frame generation, compute shaders, or mesh shaders as implemented features. They are not part of the current documented core.

## 4. Architecture principles

### Intended render pipeline

1. Capture the native output target and dimensions.
2. If ModernOPT is enabled and the active render path is safe, allocate/reuse an off-screen world target at the effective internal resolution.
3. Render the world into that target.
4. Keep HUD/GUI at native output resolution.
5. Reconstruct/copy the world image to the native target using the selected mode.
6. Apply optional compatible post-processing (e.g. FXAA) only when it does not conflict with the active shader-pack pipeline.
7. Restore framebuffer bindings, viewport, projection matrix, blend/depth state, shader colour, and any temporarily changed dimensions even if a pass fails.
8. On unsupported target allocation, shader compilation, or pipeline detection, bypass the optimized pass and keep the game renderable.

This is a design contract, not proof that every shader pack follows the path. Check current code and test in-game before extending it.

### Hard engineering rules

- Never leave the main render target, viewport, projection matrix, depth/blend state, or shader colour modified after a pass.
- Avoid per-frame allocations, repeated reflection, redundant framebuffer copies, repeated shader compilation, and unnecessary driver queries.
- Reuse targets only when dimensions and formats are compatible; destroy them on resize/reload/shutdown and handle partial allocation failures.
- Prefer explicit, reversible state transitions and try/finally restoration.
- Do not use frequent glGetError polling or blocking GPU timer queries in the hot path. Asynchronous timestamp queries are permitted only when results are polled for availability and read later.
- Do not treat CPU submission time as GPU execution time.
- Avoid fragile mixins/overwrites against Sodium or Iris internals where a narrower hook/fallback is possible.
- Never cancel an entire Iris shader/shadow pass to optimize a single effect.
- Keep the master switch authoritative: disabling ModernOPT must bypass all ModernOPT rendering changes and stop its active benchmark capture.
- Optional optimizations must be independently disableable and must not make base rendering fail.
- UI sliders/toggles must reflect actual supported behaviour; don't ship dummy controls as completed features.
- Any visual/performance change must be measured against a baseline, not justified by intuition.

## 5. Long-term roadmap (priority order)

Phases are gates, not deadlines. Do not skip a gate because a later feature sounds more exciting.

### Phase 0 — Establish a trustworthy baseline (highest priority)

Goal: determine exactly which current paths work and prevent regressions.

- Build from a clean checkout with Java 21 and the configured Gradle wrapper/toolchain.
- Audit every config option against its call sites. Mark each as working, partial, reserved, or inert; remove misleading UI only after considering config compatibility.
- Check that the master switch truly bypasses every ModernOPT render modification and benchmark activity.
- Confirm render scale is applied to intended world targets and does not accidentally scale HUD, GUI, menus, or screenshots.
- Check target/resource cleanup on window resize, fullscreen changes, resource reload, world transitions, exceptions, and toggling settings.
- Verify fallback paths keep a frame visible if an FBO or shader is unavailable.
- Add a compact manual test matrix and record exact game/mod/driver/shader versions for every result.
- Run a clean build and inspect the produced JAR. CI green is a build gate, not a runtime gate.

Exit criteria: no known state-leak or crash on toggling/resizing; master switch works; baseline screenshots and benchmark CSVs are reproducible; every feature's support status is documented.

### Phase 1 — Correct fixed render scale

Goal: make fixed internal-resolution scaling correct before adding more rendering features.

- Test 100%, 90%, 75%, 50%, and 25% at multiple window aspect ratios and fullscreen/windowed modes.
- Confirm output fills the screen without black bars, cropping, aspect-ratio distortion, or upside-down/offset output.
- Confirm native-resolution text, hotbar, inventory, crosshair, menus, and tooltips.
- Validate depth, transparency, particles, entities, hand rendering, weather, clouds, entity post chains, and screenshot behaviour.
- Confirm dimensions round safely and obey minimum target sizes.
- Verify changing scale does not cause perceived low FPS, input latency, stutter, or freezes.
- Treat Iris as a separately gated path. If correctness cannot be guaranteed for a shader pack, fail closed and report that scale is bypassed rather than rendering a broken frame.

Exit criteria: visual correctness is repeatable at all scale levels in vanilla/no shader pack; no FPS/frametime regression attributable to the mod; Iris support is explicitly classified as supported, partial, or bypassed.

### Phase 2 — Find and remove the real performance bottleneck

Goal: explain the previously observed modest gain (about 1.4–1.5x FPS at 50% scale in some tests) before rewriting the renderer.

- Collect matched 100% vs 50% CSVs in the same warmed scene, view direction, weather, shader settings, FPS cap, and VSync state; repeat runs at least twice.
- Inspect actual output and world-target dimensions and pixel ratio first—do not assume the requested scale is the effective scale.
- Compare average FPS, p95/p99 frame time, spike count, process/system CPU, heap, world-pass CPU time, and post-process CPU time.
- Classify likely CPU/game-thread, pixel-shading/GPU, shader-pack, frame-cap/VSync, allocation/transfer, or post-processing bottleneck. Async timestamp intervals are approximate GPU timings for queued world/post work; still validate against in-game behavior and external profiling.
- If data points to GPU-bound rendering, use a suitable external GPU profiler or controlled shader-pack/pass tests; do not infer GPU timing from CPU timings alone.
- Make one measured change at a time and retain a baseline for comparison.

Exit criteria: a written bottleneck diagnosis with evidence, plus a repeatable benchmark showing the effect of any optimization.

### Phase 3 — Simplify and optimize the existing post-processing pipeline

Goal: reduce overhead without replacing Sodium or Iris.

- Map each render pass and framebuffer copy in GameRendererMixin and Fsr1Renderer.
- Remove only proven redundant copies/conversions; preserve a safe fallback.
- Reuse intermediate targets where formats, dimensions, sampling, and lifetimes allow.
- Avoid unnecessary full-resolution scratch buffers and identity passes.
- Cache shader/program/reflection discovery outside the frame hot path.
- Measure CPU overhead, frame-time percentiles, GPU frame time where available, and visual output before/after each change.
- Do not merge multiple unmeasured optimizations into one opaque change.

Exit criteria: lower or equal frame time and memory/buffer cost in matched tests, no visual regression, and all fallback paths still work.

### Phase 4 — Compatibility hardening and release-quality settings

Goal: make the stable core usable in real modpacks.

Test and document a matrix covering:
- Vanilla rendering / no Sodium / no Iris.
- Supported Sodium versions, including the maintainer's target 0.8.13 where available.
- Iris with no shader pack and with Complementary Unbound.
- Window resize, fullscreen, resource reload, world join/leave, UI screens.
- Create/Flywheel and other rendering-heavy mods where practical.
- Supported graphics vendors/drivers, at least one older/low-end device and one modern device if available.

- Detect known incompatible render paths conservatively.
- Keep unsupported effects isolated instead of disabling the whole mod.
- Make the standalone config screen and Sodium integration agree on values.
- Ensure English/Russian labels, defaults, tooltips, and documentation match actual behaviour.
- Publish only explicit tested compatibility claims.

Exit criteria: reproducible smoke-test checklist and clear per-combination support notes.

### Phase 5 — Spatial reconstruction quality and anti-aliasing

Goal: improve image quality without destabilizing the renderer.

- First verify and tune existing FSR1 EASU + RCAS implementation and its native textureGather/explicit-fetch fallback. FP16 EASU should use native OpenGL `textureGather` rather than the RenderPearl/Vulkan-translation explicit-`texelFetch` workaround; compare FP16 and FP32 on the same GPU because FP16 is not inherently faster.
- Compare FSR1 with bilinear, nearest, and SoftScale at identical internal resolutions.
- Validate shader orientation, colour, edge stability, sharpening, and performance.
- Make SSAA/MSAA/FXAA interactions explicit, including precedence and unsupported modes.
- Ensure FXAA is skipped only when its pass conflicts with Iris/shaders; do not switch off unrelated ModernOPT features.
- Only after phases 0–4 are stable, evaluate temporal anti-aliasing (TAA) and/or FSR2 as separate designs. Temporal methods require motion vectors, history buffers, jitter/reprojection, disocclusion handling, and careful reset rules; do not ship a fake spatial approximation under a temporal name.
- Any temporal implementation must be tested for shimmer, ghosting, camera movement, particles/transparency, resource reload, and Create/Flywheel interactions.

Exit criteria: documented image-quality/performance comparisons and no unresolved severe temporal artefacts before enabling a temporal mode by default.

### Phase 6 — Dynamic resolution (only after fixed scale is reliable)

Goal: adapt internal resolution to a target frame rate without oscillation or bad frame pacing.

- Audit the existing RenderScaleController integration first; do not rewrite blindly.
- Use stable frame-time measurements, hysteresis/dead band, bounded scale steps, and a sensible adjustment interval.
- Do not chase transient FPS spikes or react to a fixed FPS cap as though it were a GPU bottleneck.
- Keep user min/max scale bounds and target FPS clear in the UI.
- Show the effective scale in the HUD and provide an immediate off switch.
- Compare frametime distributions and perceived smoothness, not only average FPS.

Exit criteria: stable scale behaviour in a repeatable moving scene without oscillation, visible pumping, or extra stutter.

### Phase 7 — Additional optimizations (evidence-driven)

Only pursue optimizations supported by profiling:
- reduce redundant post-process work and allocations;
- improve diagnostic clarity;
- consider safe particle draw-distance culling only when its CPU cost is justified;
- investigate compatibility with existing culling mods rather than implementing a competing chunk/terrain culler;
- keep features modular, optional, and independently testable.

Do not implement compute shaders, mesh shaders, a replacement terrain renderer, or speculative low-level systems without a demonstrated bottleneck and a separate design proposal.

### Phase 8 — Frame Generation (explicitly deferred)

Do not start this phase until the maintainer explicitly asks for it and phases 0–5 have a stable baseline.

Prior history: earlier FG work was associated with a high FPS counter but a strong 24–30 FPS/latency sensation and freezes after ModernOPT interventions. The maintainer asked to defer FG and focus on upscalers first.

If reopened in the future:
- begin with a written architecture proposal and isolated experimental branch;
- do not alter the established scaling/upscaling/AA pipeline to make FG work;
- prefer disabling FG on conflict rather than disabling world rendering;
- keep HUD/GUI native-resolution and never interpolate HUD pixels;
- define frame pacing, latency, camera-motion, UI, and fallback tests before implementation;
- make no claims of working FG until in-game testing confirms it.

## 6. Benchmark protocol

For any claimed FPS improvement:

1. Use the same game build, mod list, world, location, camera direction, weather, resolution, shader/resource packs, FPS cap, VSync, and graphics settings.
2. Warm up the scene and wait for chunk loading to settle.
3. Capture at least 30–60 seconds at the baseline (normally 100% scale).
4. Capture the same interval at the changed setting (e.g. 50%).
5. Repeat each condition at least twice; report mean and variability.
6. Compare p95/p99 frame time, spikes, CPU load, actual target dimensions, and the effective scale—not only the FPS counter.
7. Attach CSVs and relevant redacted “Render diagnostics” log lines, including CPU/GPU timings, target dimensions and main-target restoration status.
8. State clearly whether the result is a build test, a launch test, or an actual in-game runtime test.

The current CSV does not measure VRAM or GPU temperature. CPU wall-clock time around a draw call is not GPU execution time; asynchronous timestamp queries measure queued GPU intervals when supported, and should be cross-checked during runtime testing.

## 7. Definition of done

A feature is not finished merely because code exists or CI is green. A feature is done when:
- it builds from a clean checkout;
- the intended JAR artifact is available;
- its settings and documentation match actual behaviour;
- normal and failure/fallback paths are checked;
- visual correctness is verified in-game where relevant;
- before/after measurements are provided for performance claims;
- compatibility limitations are explicit;
- English and Russian user-facing text is updated;
- provenance/licence notices are preserved.

When runtime testing is unavailable, label the feature “compiled, runtime-unverified”—never claim it is fully tested.

## 8. Immediate next actions

1. Obtain matched benchmark CSVs for 100% and 50% scale and inspect them before choosing a performance rewrite.
2. Audit the current GameRendererMixin lifecycle and config call sites, especially master-disable behaviour, target restoration, scaling activation, and shader-pack bypass.
3. Test fixed render scale in-game with no shader pack first; verify image fill/aspect ratio and native-resolution HUD/GUI.
4. Test the exact Sodium/Iris/Complementary setup separately and document any bypasses or failures.
5. Make one focused correction at a time, build, inspect the JAR, and test the artifact before adding new features.
6. Only after this baseline is stable, continue FSR1 tuning and evaluate future temporal upscalers.

## 9. Continuity notes for future assistants

- The maintainer wants direct repository work and a real build artifact; do not paste whole source files into chat.
- Be concise about status but precise: report commit, build result, artifact location, and exactly what was or was not runtime-tested.
- Do not say “done” while only a source edit or in-progress CI job exists.
- Ask for benchmark files only if they are not already attached or available; inspect uploaded CSV/log files directly.
- Keep work incremental and focused. Avoid unrelated rewrites of the rendering core.
- The maintainer explicitly decided to leave compute/mesh shaders aside for now.
