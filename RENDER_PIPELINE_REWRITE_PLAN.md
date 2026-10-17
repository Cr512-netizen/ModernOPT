# ModernOPT render-pipeline rewrite plan

Status: staged rewrite proposal for Minecraft 1.21.1 / NeoForge with Sodium and Iris. This document is a plan, not a claim that the new renderer is already implemented or validated in-game.

## Why the current path must be replaced

The current world-render path is concentrated in `GameRendererMixin`. It temporarily replaces Minecraft's main render target, changes auxiliary target dimensions, performs an AA/upscale pass, and restores state in a RETURN injection. That is too much lifecycle responsibility in a single mixin and is fragile around Iris, Sodium, shader-pack framebuffers, resize events, and third-party render targets.

The Iris shadow throttling prototype also demonstrated an unsafe assumption: cancelling `IrisRenderingPipeline.renderShadows()` is not sufficient to preserve a valid cached shadow frame. The map, its matrices, and Iris' target/PREPARE lifecycle must remain a coherent unit.

## Reference implementations studied

- **Vira (MIT, Minecraft 1.21.1 NeoForge)**: useful design reference for spatial FSR1 upscaling and shadow-refresh pacing. Its documented shadow optimization retains the map together with the matrices that generated it. ModernOPT must independently implement and validate that contract; it must not copy code wholesale.
- **Sodium + Iris**: remain the owners of terrain submission, shader-pack passes, and shader-managed shadow targets. ModernOPT should integrate at narrow, explicit boundaries rather than replace their internal targets without a renderer-specific adapter.
- **Minecraft RenderTarget/PostChain lifecycle**: use their allocation/resize contracts and restore all GL state on every exit path.

## Target architecture

1. **Render feature policy** — one pure decision stage selects exactly one mode for a frame: vanilla, render scale + spatial upscale, or one AA mode. Shader packs own their AA path; ModernOPT must not run a second AA pass over their output.
2. **Target manager** — owns only ModernOPT-created targets, tracks dimensions/format/sample count, reuses allocations, and tears them down on resize/resource reload. It must never claim ownership of Iris targets.
3. **World-pass adapter** — a small version-specific adapter for Minecraft/Sodium/Iris. It must validate target identity, dimensions, and framebuffer completeness before activating a feature; otherwise it falls back to vanilla for that frame.
4. **Post-process graph** — explicit input/output passes with a single declared resolve point. HUD, GUI, text, hand overlays, and menus remain at native resolution.
5. **AA backends** — FXAA, MSAA, and SSAA are mutually exclusive, separately measurable backends. MSAA's sample count is real hardware work, not a free quality toggle. 8x is the hard maximum and may still be substantially slower than 2x/4x; performance work must measure GPU time, not only FPS.
6. **Iris shadow adapter** — separate from the world-resolution path. Shadow throttling may only skip a refresh after the adapter can preserve and restore both shadow-map contents and all matching shadow matrices/state. If that contract is not available, render shadows every frame.
7. **Diagnostics and fallback** — record mode, dimensions, target identity, GPU timing where supported, resolve count, and fallback reason. No per-frame allocation, synchronous GPU readback, or unbounded GL error polling.

## Staged implementation

### Stage A — safety and observability
- Keep the experimental custom world-render path disabled until it passes a live shader-pack test.
- Cache Iris shadow refreshes as one unit: suppress shadow-target clears and shadow compute passes, skip only the shadow geometry/composite method, retain its previous MODELVIEW/PROJECTION matrices, and still run Iris' non-shadow PREPARE stage. Fail open on unknown target lifecycle or required full clear.
- Make MSAA color resolve lazy and cached until the target is written again; cap the supported UI/config range at 8x and fall back to the highest successfully allocated level (8x → 4x → 2x).
- Keep build/test artifacts on a separate feature branch; never merge to the default branch without review.

### Stage B — target and policy extraction
- Extract target allocation/reuse/destruction into `RenderTargetManager`; the manager owns only ModernOPT targets and releases them when `GameRenderer` closes.
- Extract fixed-function and textured fallback copies into `FramebufferBlitter`.
- Move feature precedence into `RenderPipelinePolicy`; keep shader-pack checks explicit at the adapter boundary.
- Continue reducing `GameRendererMixin` to lifecycle orchestration; do not switch rendering behavior in the same commit as each extraction.

### Stage C — safe first backend
- Reintroduce only spatial render scaling without shaders, with exact size/viewport restoration and native-resolution HUD.
- Add resize/fullscreen/resource-reload checks and fallback-to-vanilla behavior.
- Test 100%, reduced scale, window resize, fullscreen toggles, hand/player visibility, entity outlines, and modded render targets.

### Stage D — shader-pack adapter
- Add an Iris-specific integration only after verifying the exact supported Iris version and target lifecycle.
- Test Complementary, shadow casters, block entities, hand/player, world transitions, resource reloads, and resize.
- If any target/matrix invariant fails, disable the integration for that frame rather than corrupting the image.

### Stage E — post-processing and AA
- Add one post-process backend at a time (bilinear/nearest, then FSR1, then FXAA).
- Implement MSAA with a clear ownership/resolve contract and no repeated resolves in one frame.
- Benchmark 2x/4x/8x on the same scene; 8x is the hard maximum.

## Acceptance criteria

- No missing shadows, player, hand, outlines, block entities, or green/triangle artifacts.
- No visible stutter or lower perceived frame pacing caused by the mod.
- 100% scale with all optional passes off is behaviorally equivalent to vanilla.
- Shader-pack mode never receives ModernOPT's second AA pass.
- Skipped shadow frames preserve the previous shadow textures and their matching matrices; refresh is forced after target reloads/full clears.
- Every allocation and resize failure falls back to vanilla; GL framebuffer, viewport, projection, depth/blend state, and current target are restored.
- CI build success is necessary but not sufficient: a real in-game Complementary test is required before calling a render stage complete.
