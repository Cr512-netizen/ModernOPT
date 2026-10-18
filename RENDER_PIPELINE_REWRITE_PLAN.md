# ModernOPT render-pipeline completion plan

Working branch: `fix/shadow-cache-render-architecture`. Master render switch: disabled by default. Frame Generation is explicitly out of scope. Do not merge to `main` until review and in-game acceptance are complete.

## Objective

Complete all source-level work in one pass: audit target ownership and failure paths, make render-pass selection authoritative, reconcile documentation with actual configuration, build the final branch head, and provide one test artifact. CI cannot prove visual correctness or real performance; those remain in-game acceptance checks.

## Required implementation invariants

1. **Kill switch and fallback**
   - The master switch defaults to off.
   - Failed shader/upscale passes fall back to a direct opaque copy where possible.
   - The active feature selection is carried through the whole post-process stage; no pass re-derives policy from raw configuration.
   - Allocation/resize failure must not leave an invalid target cached for reuse.
2. **Targets and GL state**
   - ModernOPT owns only its private targets.
   - World targets are checked for valid dimensions and GL limits.
   - Restore projection, vertex sorting, depth/blend enablement, depth-write mask, main target and viewport after composition.
   - HUD/GUI remain at native window resolution.
3. **Render scale**
   - Preserve output dimensions while scaling only world targets.
   - 100%, 90%, 75%, and 50% need live checks, as do resize, fullscreen and resource reload.
   - If auxiliary targets cannot be synchronized, restore native dimensions and bypass the scaled path for that frame.
4. **Upscaling and AA**
   - FSR1 EASU + RCAS uses FP16 only as an optional path and retains FP32 fallback.
   - Bilinear, nearest-neighbour and SoftScale remain distinct modes.
   - FXAA, SSAA and MSAA obey one explicit policy decision. MSAA is capped at 8x with fallback 8x → 4x → 2x.
   - Shader-pack output must not receive a second ModernOPT AA pass.
5. **Iris/shadows**
   - Iris remains optional. Unknown target/matrix conditions must fail open to per-frame shadow rendering.
   - Shadow refresh throttling stays disabled by default until the map and matching matrices are validated as one unit.
   - Live tests must include player/hand/entities, outlines, shadow casters, world changes, resource reloads and Complementary.
6. **Diagnostics/performance**
   - Label CPU timings as CPU submission/wall time; use asynchronous GPU queries only when supported and read results only when available.
   - Compare frame-time percentiles and stutters, not only the FPS counter.
   - Do not claim a performance gain without repeated same-scene captures.
7. **Packaging/docs**
   - English and Russian docs agree with actual config/UI values.
   - Artifact SHA must correspond to the exact successful CI head SHA.
   - Keep the PR open and `main` unchanged until acceptance.

## Current status

- Architecture extraction exists: `RenderTargetManager`, `FramebufferBlitter`, `RenderPipelinePolicy`, `WorldPostProcessStage`, and `WorldUpscalePass`.
- FSR1 and simple spatial shader paths exist, but build success does not prove that each GPU path executes correctly.
- The experimental render path is disabled by default and may be explicitly enabled for testing.
- Source-level pass selection is being tightened so FXAA execution follows the policy decision instead of independently reading the raw setting.
- Live Minecraft tests are not performed by CI and must be supplied by the maintainer.

## Acceptance checklist

- [ ] CI succeeds for the final branch-head SHA and the artifact is downloadable.
- [ ] Master switch off leaves the world render path untouched.
- [ ] At 100% with optional passes off, output matches native rendering.
- [ ] 90%, 75%, and 50% fill the screen correctly; HUD, text, inventory, hand, player, entities and outlines remain correct.
- [ ] Resize, fullscreen, resource reload and world load/unload do not leave stale targets.
- [ ] Complementary/Iris is tested with shadows on; unsupported conditions bypass the relevant optimization safely.
- [ ] FXAA, SSAA, MSAA and FSR1 precedence matches the selected policy; shader-pack AA is never doubled.
- [ ] Same-scene CSV captures compare frame pacing and enabled-vs-disabled timing.
- [ ] English/Russian docs, config labels and release notes agree with implemented features.
