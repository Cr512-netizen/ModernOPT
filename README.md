# ModernOPT

ModernOPT is a clean rebuild for Minecraft Java 1.21.1 on NeoForge. Frame Generation and legacy rendering code are intentionally excluded.

## Current rendering core

- Client-side master switch and internal render scale (50–100%).
- World/first-person pass is rendered to a dedicated lower-resolution target and scaled into the window-sized main target.
- HUD, menus, and other GUI rendering happen after the world pass and remain at native window resolution.
- Render scaling is fail-closed when Iris is loaded because the shader pipeline requires a dedicated compatibility bridge. Do not assume Iris compatibility yet.
- Build and test with the exact mod set before treating the renderer as stable.

## Build

Requires Java 21. CI runs clean build on pushes and pull requests. The build artifact is uploaded by GitHub Actions.

## Русский

ModernOPT — чистая переработка для Minecraft Java 1.21.1 на NeoForge. Frame Generation и старый код рендера не переносятся.

- Главный выключатель мода и масштаб внутреннего рендера 50–100%.
- Мир и вид от первого лица рисуются в отдельную текстуру меньшего разрешения, затем масштабируются в основной буфер окна.
- HUD, меню и GUI рисуются после прохода мира и остаются в исходном разрешении.
- При наличии Iris масштабирование безопасно отключается: для шейдерного конвейера нужен отдельный слой совместимости. Совместимость с Iris пока не заявляется.
- После изменений обязательно проверять сборку и тестировать в реальном клиенте с нужным набором модов.
