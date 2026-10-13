# ModernOPT

ModernOPT is being rebuilt from a clean base for **Minecraft Java 1.21.1 + NeoForge**.

## Current scope

- Client-side master enable/disable setting.
- Bounded render-scale configuration (50–100%).
- Render sizing policy isolated from Minecraft's main framebuffer.
- CI build that compiles the mod and uploads the JAR as an artifact.

**Important:** the percentage is configuration/core plumbing at this stage; world-only render-target switching is not enabled yet. The renderer must keep GUI/HUD at native resolution and fail closed if a compatible target path cannot be established. No Frame Generation code is included.

## Build

Requires Java 21 and Gradle 9.2.1:

```sh
gradle clean build
```

The resulting JAR is in `build/libs/`.

## Совместимость и статус

ModernOPT пересобирается с нуля для **Minecraft Java 1.21.1 + NeoForge**.

Сейчас заложены переключатель полного отключения, настройка масштаба 50–100%, изолированная политика расчёта размеров рендера и CI-сборка. Само переключение world-only render target пока не включено: нельзя уменьшать главный framebuffer вслепую, иначе вместе с миром уменьшаются HUD/GUI и возможны конфликты с Sodium/Iris.

Frame Generation намеренно не входит в эту базовую версию. Следующий этап — реализовать и проверить безопасный путь рендера мира с сохранением HUD/GUI в родном разрешении.
