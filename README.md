# ModernOPT

<p align="center">
  <strong>A lightweight, experimental rendering and upscaling mod for Minecraft Java Edition 1.21.1.</strong><br>
  <em>Настраиваемое масштабирование и экспериментальные улучшения рендеринга для Minecraft Java Edition 1.21.1.</em>
</p>

<p align="center">
  <a href="https://github.com/Cr512-netizen/ModernOPT/actions/workflows/build.yml"><img alt="Build" src="https://github.com/Cr512-netizen/ModernOPT/actions/workflows/build.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/License-MIT-blue.svg"></a>
  <a href="https://minecraft.net/"><img alt="Minecraft 1.21.1" src="https://img.shields.io/badge/Minecraft-1.21.1-green.svg"></a>
  <a href="https://neoforged.net/"><img alt="NeoForge" src="https://img.shields.io/badge/Loader-NeoForge-orange.svg"></a>
</p>

**Project status: experimental / alpha.** ModernOPT is under active development. A successful build does not mean every feature or mod combination has been runtime-tested. Back up your worlds and configuration before testing, and report reproducible issues with logs and mod versions.

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Configuration](#configuration)
- [Compatibility and limitations](#compatibility-and-limitations)
- [Building from source](#building-from-source)
- [Reporting bugs and contributing](#reporting-bugs-and-contributing)
- [Privacy](#privacy)
- [License and third-party notices](#license-and-third-party-notices)
- [Disclaimer and trademarks](#disclaimer-and-trademarks)
- [Русский](#русский)

## Features

- **Render-scale control:** adjust the internal world-render resolution from 25% to 100% of the window resolution.
- **Spatial upscalers:** FSR 1 (EASU + RCAS), bilinear, nearest-neighbour, and SoftScale reconstruction modes.
- **SSAA:** optional 2× width and 2× height world rendering followed by downsampling. This processes four times as many world pixels and can be expensive. SSAA takes priority over render scale and MSAA.
- **MSAA:** optional world-pass multisampling with Off, 2×, 4×, 8×, and 16× choices; if 16× is unavailable, ModernOPT retries at 8×.
- **FXAA:** optional post-process edge smoothing.
- **Native-resolution interface:** the intended render pipeline keeps HUD and GUI rendering at the window resolution.
- **Client-side settings:** rendering options are stored in the client configuration. The master switch is intended to disable ModernOPT's rendering interventions.

Features may be bypassed when the active render pipeline is incompatible. See the compatibility notes below and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for implementation and attribution details.

## Requirements

- Minecraft: **Java Edition 1.21.1**
- Mod loader: **NeoForge** (the project is built against the NeoForge 21.1 line)
- Java: **21**
- A graphics device and driver supporting the Minecraft/NeoForge rendering environment

Sodium is optional unless a future release explicitly states otherwise. Do not assume compatibility with every version of Sodium, Iris, shader pack, graphics driver, or rendering-related mod.

## Installation

1. Install Minecraft Java Edition 1.21.1 and a compatible NeoForge installation.
2. Open the [GitHub Actions builds](https://github.com/Cr512-netizen/ModernOPT/actions/workflows/build.yml).
3. Choose a successful run for the commit you want to test and download its **ModernOPT** artifact from the run's Artifacts section.
4. Place the mod JAR in that Minecraft instance's `mods` folder.
5. Launch the game and check the log if the game fails to start.

Artifacts are development builds and may change without notice. Use the version/commit that matches the source you intend to test. Do not install multiple versions of the same mod at once.

## Configuration

ModernOPT exposes its rendering options in Sodium's own options UI when a compatible Sodium version is installed. The master switch is off by default; leave it off if you encounter visual corruption. Settings cover:

- Master enable/disable switch
- Render scale and upscaler selection
- SSAA
- FXAA
- MSAA

The on-disk client configuration uses keys such as `rendering.renderScalePercent`, `rendering.upscalerMode`, `rendering.ssaaEnabled`, `rendering.fxaaEnabled`, and `rendering.msaaSamples`. Exact availability depends on the build. Back up the config before editing it manually.

## Compatibility and limitations

- **Iris / shader packs:** full render-scale integration is not currently claimed. The mod is designed to fail closed or bypass selected passes when a shader-pack pipeline is active. Do not assume that world scaling, FXAA, or MSAA will work with Iris.
- **Sodium:** integration and appearance may vary between Sodium versions. Compatibility is not guaranteed unless a specific version is listed in a tested release.
- **Other rendering mods:** mods that replace or redirect the world render target, post-processing, framebuffers, or game renderer may conflict.
- **Performance and visual quality:** upscaling can improve GPU-bound performance in some scenarios, but it does not guarantee higher FPS or lower frame times. SSAA is particularly demanding. Results vary by GPU, driver, resolution, resource packs, shader packs, and mod list.
- **Frame Generation:** not included in the current rendering core. Do not assume that frame generation is implemented.

Please include the exact Minecraft, NeoForge, ModernOPT, Sodium/Iris, GPU/driver, and shader-pack versions when reporting compatibility issues.

## Building from source

1. Install a Java 21 JDK.
2. Clone this repository.
3. Run the Gradle build using the wrapper if one is present, or a compatible Gradle installation:

   ```sh
   gradle --no-daemon clean build
   ```

4. Find the output JAR in `build/libs/`.

GitHub Actions runs the project build on pushes and pull requests. A green CI build confirms that the configured build task completed; it does **not** replace in-game testing across supported hardware and mod combinations.

## Reporting bugs and contributing

- Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.
- Report reproducible bugs through [GitHub Issues](https://github.com/Cr512-netizen/ModernOPT/issues).
- For suspected security vulnerabilities, follow [SECURITY.md](SECURITY.md) rather than posting exploit details publicly.
- Be respectful and follow the project's [Code of Conduct](CODE_OF_CONDUCT.md).
- Do not include passwords, access tokens, private server addresses, personal data, or other secrets in logs and issue reports.
- Do not submit code, assets, shader code, or documentation copied from another project unless you have permission and the licence permits redistribution. Preserve required notices and document provenance in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Privacy

ModernOPT is a client-side Minecraft mod. The project does not intentionally require a user account or collect personal information as part of its rendering features. The mod's local configuration is stored by the Minecraft/NeoForge environment. This statement is not a guarantee about third-party launchers, mod platforms, crash-reporting tools, or other installed mods.

When sharing logs or crash reports, review them first and redact account identifiers, access tokens, private addresses, and other sensitive information.

## License and third-party notices

The original project code is released under the **MIT License**; see [LICENSE](LICENSE). Some components are derived from or adapted from third-party projects and are governed by their own notices and licence terms. These exceptions are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Do not interpret the repository's MIT licence as replacing the licence of third-party components.

Contributions are welcome. By submitting a contribution, you confirm that you have the right to submit it and agree that it may be distributed as part of this project under the project's MIT licence, while any existing third-party terms remain applicable.

## Disclaimer and trademarks

ModernOPT is an independent community project. It is not affiliated with, endorsed by, or sponsored by Mojang Studios, Microsoft, NeoForge, CaffeineMC/Sodium, Iris, or Advanced Micro Devices, Inc. Product and project names are used only to identify the relevant software and compatibility targets. All trademarks belong to their respective owners.

The software is provided “as is”, without warranties or guarantees, to the maximum extent permitted by applicable law. Rendering changes may cause visual defects, crashes, performance regressions, or incompatibilities. Back up important data and use the mod at your own risk. Nothing in this README overrides mandatory rights provided by applicable law.

---

# Русский

**ModernOPT** — экспериментальный мод для управления разрешением внутреннего рендера и масштабирования изображения в Minecraft Java Edition 1.21.1.

**Статус проекта: alpha / экспериментальная разработка.** Мод активно меняется. Успешная компиляция не означает, что все функции проверены в игре со всеми видеокартами и сборками модов. Перед тестированием сохраните резервные копии миров и конфигурации.

## Возможности

- **Масштаб рендера:** от 25% до 100% разрешения окна.
- **Пространственные апскейлеры:** FSR 1 (EASU + RCAS), билинейный, по ближайшему соседу и SoftScale.
- **SSAA:** рендер мира в разрешении 2× по ширине и высоте с последующим уменьшением. Обрабатывается в четыре раза больше пикселей; это может быть очень затратно. SSAA имеет приоритет над масштабом рендера и MSAA.
- **MSAA:** мультисэмплинг прохода мира с режимами выкл., 2×, 4×, 8× и 16×; если 16× недоступен, мод повторяет попытку в режиме 8×.
- **FXAA:** дополнительное сглаживание контуров.
- **Интерфейс в исходном разрешении:** предполагается, что HUD и GUI рисуются в разрешении окна.
- **Клиентские настройки:** параметры рендеринга хранятся в клиентской конфигурации. Главный переключатель предназначен для отключения вмешательства ModernOPT в рендеринг.

Ряд функций может пропускаться, если активный графический конвейер несовместим. Подробности и сведения об исходных компонентах — в разделах ниже и в [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Требования

- Minecraft **Java Edition 1.21.1**
- **NeoForge** (сборка ориентирована на ветку NeoForge 21.1)
- **Java 21**
- Совместимые с Minecraft графическая карта и драйвер

Sodium не является обязательной зависимостью, если в описании конкретного релиза не указано иное. Не следует считать гарантированной совместимость со всеми версиями Sodium, Iris, шейдер-паками, драйверами и модами рендеринга.

## Установка

1. Установите Minecraft Java Edition 1.21.1 и совместимый NeoForge.
2. Откройте [сборки GitHub Actions](https://github.com/Cr512-netizen/ModernOPT/actions/workflows/build.yml).
3. Выберите успешный запуск для нужного коммита и скачайте артефакт **ModernOPT** в разделе Artifacts.
4. Поместите JAR-файл мода в папку `mods` соответствующего экземпляра Minecraft.
5. Если игра не запускается, проверьте журнал и версии установленных модов.

Артефакты являются тестовыми сборками и могут меняться без предупреждения. Используйте сборку, соответствующую нужному коммиту. Не устанавливайте одновременно несколько версий одного мода.

## Настройки

Если установлена совместимая версия Sodium, настройки ModernOPT доступны в собственном меню настроек Sodium. Главный переключатель по умолчанию выключен; оставьте его выключенным, если заметите графические искажения. Настройки включают:

- Полное включение и отключение ModernOPT
- Масштаб рендера и выбор апскейлера
- SSAA
- FXAA
- MSAA

В клиентском конфиге используются ключи, например `rendering.renderScalePercent`, `rendering.upscalerMode`, `rendering.ssaaEnabled`, `rendering.fxaaEnabled` и `rendering.msaaSamples`. Точный набор параметров зависит от версии. Перед ручным редактированием сделайте резервную копию.

## Совместимость и ограничения

- **Iris / шейдер-паки:** полноценная интеграция масштабирования рендера пока не заявляется. При активном шейдерном конвейере некоторые проходы могут безопасно пропускаться. Не рассчитывайте на работу масштабирования, FXAA или MSAA с Iris без отдельного подтверждения.
- **Sodium:** интеграция и внешний вид могут отличаться между версиями. Совместимость не гарантируется, если конкретная версия не указана в проверенном релизе.
- **Другие моды рендеринга:** конфликты возможны с модами, меняющими основной рендерер, буферы, framebuffer или постобработку.
- **Производительность и качество:** апскейлинг иногда помогает при ограничении производительностью GPU, но не гарантирует рост FPS или снижение времени кадра. SSAA особенно требователен к ресурсам. Результат зависит от видеокарты, драйвера, разрешения и набора модов.
- **Frame Generation:** в текущем ядре рендеринга не реализован. Не следует считать, что мод поддерживает генерацию кадров.

При сообщении об ошибке укажите точные версии Minecraft, NeoForge, ModernOPT, Sodium/Iris, видеокарты и драйвера, а также используемые шейдер-паки.

## Сборка из исходников

1. Установите JDK 21.
2. Клонируйте репозиторий.
3. Выполните сборку Gradle через wrapper, если он присутствует, или совместимую установку Gradle:

   ```sh
   gradle --no-daemon clean build
   ```

4. Готовый JAR появится в `build/libs/`.

GitHub Actions запускает сборку при отправке изменений и создании pull request. Успешный CI подтверждает выполнение настроенной задачи сборки, но не заменяет тестирование в игре на разных конфигурациях.

## Ошибки, вклад в проект и правила общения

- Перед pull request прочитайте [CONTRIBUTING.md](CONTRIBUTING.md).
- Ошибки с воспроизводимыми шагами отправляйте через [GitHub Issues](https://github.com/Cr512-netizen/ModernOPT/issues).
- О потенциальных уязвимостях сообщайте по инструкции [SECURITY.md](SECURITY.md), а не публикуйте детали эксплуатации в открытом issue.
- Соблюдайте [Кодекс поведения](CODE_OF_CONDUCT.md).
- Не публикуйте в логах пароли, токены доступа, личные данные и приватные адреса серверов.
- Не добавляйте чужой код, шейдеры, изображения или документацию без необходимых прав. Сохраняйте обязательные уведомления и указывайте происхождение компонентов в [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Конфиденциальность

ModernOPT — клиентский мод. Для работы графических функций он не требует учётную запись и не предназначен для сбора личных данных. Локальная конфигурация хранится средствами Minecraft/NeoForge. Это не является гарантией в отношении сторонних лаунчеров, сервисов сбора crash-report и других установленных модов.

Перед публикацией логов проверьте их и удалите идентификаторы аккаунтов, токены, приватные адреса и другие чувствительные сведения.

## Лицензия и сторонние компоненты

Исходный код самого проекта распространяется по **MIT License**; полный текст находится в [LICENSE](LICENSE). Некоторые компоненты основаны на сторонних проектах или адаптированы из них и могут иметь собственные условия. Они перечислены в [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Лицензия MIT на проект не отменяет лицензионные условия сторонних компонентов.

Внося вклад, вы подтверждаете, что имеете право его предоставить и соглашаетесь с распространением вклада в составе проекта по MIT License, при сохранении применимых условий сторонних лицензий.

## Отказ от гарантий и товарные знаки

ModernOPT — независимый проект сообщества. Он не связан с Mojang Studios, Microsoft, NeoForge, CaffeineMC/Sodium, Iris или Advanced Micro Devices, Inc., не одобрен и не спонсируется ими. Названия продуктов используются только для указания соответствующих программ и совместимости. Товарные знаки принадлежат их владельцам.

Программа предоставляется «как есть», без гарантий, в максимальной степени, допустимой применимым законодательством. Изменения рендеринга могут приводить к графическим ошибкам, сбоям, снижению производительности или несовместимости. Используйте мод на свой риск и сохраняйте резервные копии. Этот текст не ограничивает обязательные права, предусмотренные применимым законом.
