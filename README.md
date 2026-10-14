# ModernOPT

ModernOPT is a clean rebuild for Minecraft Java 1.21.1 on NeoForge. Frame Generation and legacy rendering code are intentionally excluded.

## Current rendering core

- Client-side master switch and internal render scale (25–100%).
- Four selectable spatial upscalers: FSR1 (EASU + RCAS), bilinear, nearest-neighbor, and SoftScale (a soft 3×3 Gaussian reconstruction filter).
- Upscaler selection is stored in the client config as `rendering.upscalerMode`: `0` = FSR1, `1` = bilinear, `2` = nearest-neighbor, `3` = SoftScale.
- Optional SSAA renders the world at 2× width and height and downsamples each 2×2 source block to the native resolution. It overrides render scale and MSAA; HUD/GUI remain native resolution. Enable with `rendering.ssaaEnabled`.
- Optional MSAA for the world pass: `rendering.msaaSamples` = `0` (off), `2`, `4`, or `8`. The multisampled color/depth buffers are resolved before upscaling/post-processing; it is bypassed with Iris shader packs and when the active target has a stencil attachment.
- Optional FXAA anti-aliasing runs after upscaling (or at native resolution when scaling is off); it is automatically skipped whenever an Iris shader pack is active. Configure it with `rendering.fxaaEnabled` in the client config.
- World/first-person pass is rendered to a dedicated lower-resolution target and scaled into the window-sized main target.
- HUD, menus, and other GUI rendering happen after the world pass and remain at native window resolution.
- Render scaling is fail-closed when Iris is loaded because the shader pipeline requires a dedicated compatibility bridge. Do not assume Iris compatibility yet.
- Build and test with the exact mod set before treating the renderer as stable.

## Build

Requires Java 21. CI runs clean build on pushes and pull requests. The build artifact is uploaded by GitHub Actions.

## Русский

ModernOPT — чистая переработка для Minecraft Java 1.21.1 на NeoForge. Frame Generation и старый код рендера не переносятся.

- Главный выключатель мода и масштаб внутреннего рендера 25–100%.
- Четыре режима масштабирования: FSR1 (EASU + RCAS), билинейный, по ближайшему соседу и SoftScale (мягкий фильтр Гаусса 3×3).
- Режим выбирается в клиентском конфиге `rendering.upscalerMode`: `0` — FSR1, `1` — билинейный, `2` — ближайший сосед, `3` — SoftScale.
- SSAA рендерит мир в разрешении 2× по ширине и высоте и сводит каждый блок 2×2 к исходному разрешению. SSAA имеет приоритет над масштабом рендера и MSAA; HUD/GUI остаются в исходном разрешении. Включается через `rendering.ssaaEnabled`.
- MSAA для прохода мира: `rendering.msaaSamples` = `0` (выкл.), `2`, `4` или `8`. Буферы цвета и глубины сводятся перед апскейлингом и постобработкой; MSAA отключается при активном шейдер-паке Iris и если основной буфер использует stencil.
- Дополнительное сглаживание FXAA выполняется после апскейлинга (или в исходном разрешении, если масштабирование выключено). При активном шейдер-паке Iris FXAA автоматически отключается. Настройка клиента: `rendering.fxaaEnabled`.
- Мир и вид от первого лица рисуются в отдельную текстуру меньшего разрешения, затем масштабируются в основной буфер окна.
- HUD, меню и GUI рисуются после прохода мира и остаются в исходном разрешении.
- При наличии Iris масштабирование безопасно отключается: для шейдерного конвейера нужен отдельный слой совместимости. Совместимость с Iris пока не заявляется.
- После изменений обязательно проверять сборку и тестировать в реальном клиенте с нужным набором модов.
