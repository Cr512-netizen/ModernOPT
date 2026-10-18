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

- [Project context and long-term roadmap](PROJECT_CONTEXT_AND_ROADMAP.md)
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
- **Render hot-path cleanup:** Iris API reflection is cached after first discovery rather than repeating class and method lookup every frame.
- **Spatial upscalers:** FSR 1 (EASU + RCAS) prefers the native GPU `textureGather` fast path, with the explicit-fetch shader retained as a compatibility fallback; bilinear, nearest-neighbour, and SoftScale reconstruction modes are also available.
- **SSAA:** optional 2× width and 2× height world rendering followed by downsampling. This processes four times as many world pixels and can be expensive. SSAA takes priority over render scale and MSAA.
- **MSAA:** optional world-pass multisampling with Off, 2×, 4×, and 8× choices. ModernOPT caps the request at 8× and retries lower supported levels (8× → 4× → 2×) if allocation fails.
- **FXAA:** optional post-process edge smoothing.
- **Native-resolution interface:** the intended render pipeline keeps HUD and GUI rendering at the window resolution.
- **Client-side settings:** use the native Sodium options page or the standalone configuration screen from the Mods list (Sodium is not required). Presets change both world scale and upscaler.
- **Performance HUD and TXT logging:** configurable FPS, approximate frame interval, Java heap usage, render scale/upscaler and particle-culling status, plus estimated 1% low FPS and frame-time p95.
- **Render diagnostics:** opt-in profiling reports CPU wall time and asynchronous OpenGL GPU timestamp measurements for the world and post-process intervals, output/internal dimensions, actual Iris RenderTarget fields where reflection permits, selected FSR1 FP16/FP32/fallback path, and whether the original main render target was restored. The benchmark CSV records these fields plus frame times, CPU load, scale and shader-pack state. GPU queries are read only after availability is reported; if unavailable, CPU-only profiling continues. No VRAM or GPU temperature readings are collected.
- **Master disable switch:** disables ModernOPT render modifications, HUD/logging, particle culling and Iris shadow-resolution changes. Benchmark capture stops when disabled; the separate profiling log can remain enabled to verify native rendering and target restoration.
- **Optional particle draw-distance culling:** skips rendering distant particles without deleting them or changing their ticking/simulation. Disabled by default and controlled by the master switch.

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

### Performance HUD and text log

Open ModernOPT's configuration screen from the Mods list even when Sodium is not installed. The Rendering page provides paired presets plus individual world-scale and upscaler controls; the other pages configure the overlay, text log, and optional particle draw-distance culling. Tooltips describe expected performance impact, which varies by hardware and scene.

Enable **Write TXT log** to append periodic samples to `config/modernopt-performance.txt`. The overlay can also show an estimated 1% low FPS, frame-time p95, and configurable background opacity. The **Benchmark** page or Sodium's **Performance HUD & benchmark** group starts a CSV capture saved under `config/modernopt-benchmarks/`. Configure capture duration (manual, 15 s, 30 s, 60 s, 120 s, or 300 s), sample every 1/2/3/5/10 frames, and independently enable CPU and heap sampling. This capture is intended for repeatable comparisons. It also records CPU load, actual native/world target dimensions and CPU-side world/post-process wall time; it does not measure GPU execution time, VRAM, or temperature. Benchmarking can add a small measurement overhead, so compare repeated runs under the same conditions.

Iris shadow-frame skipping is currently disabled internally: cancelling Iris' complete shadow pass caused shadows to become stale or disappear. The corresponding old setting is retained for config compatibility but does not currently change the shadow update rate. Iris shadow resolution scaling remains separately optional and is disabled by default.

Particle culling is a visual-only draw-distance check: particles still tick and expire normally. It is disabled by default. It can reduce draw work in particle-heavy scenes, but the per-particle distance check itself has a small CPU cost.

### Configurable render diagnostics (opt-in)

Enable `general.profilingEnabled=true` in the client config to collect detailed diagnostics. With the master switch enabled, the performance HUD shows CPU world/post time, asynchronous GPU world/post time (when OpenGL timestamp queries are supported), the selected upscaler path, actual Iris target dimensions discovered by reflection, and whether the main render target was restored. Logs repeat about every five seconds. GPU timestamps are queued asynchronously and read only after the driver marks the result available; if the driver does not support them, CPU profiling continues without waiting for GPU results.

The benchmark CSV in `config/modernopt-benchmarks/` includes `gpu_world_ms`, `gpu_post_ms`, `main_target_restored`, `upscaler_path`, and `auxiliary_targets`. GPU values may be blank/negative while no completed query is available. The target-restoration flag should remain `true`; a `false` value causes a warning in `logs/latest.log`. Iris target discovery is best-effort because Iris internals can vary between versions.

To check that the master disable switch restores native rendering, leave profiling enabled, set `general.enabled=false`, enter the world, and inspect the periodic diagnostic line. It should report `path=disabled; vanilla path` and `mainTargetRestored=true`; benchmark capture intentionally stops while disabled. For FSR1 path testing, enable the mod, select render scale below 100% and FSR1, then confirm the log says `FSR1 FP16 EASU+RCAS` or an explicit FP32/fallback path. “FP16 shaders loaded” proves only shader availability, not a runtime performance improvement.

To investigate a small gain at 50%, capture repeated 30–60 second runs at 100% and 50% in the same world, camera direction, weather, shader-pack settings, FPS cap and VSync state. Let the scene warm up first. Compare average FPS, 1% low, frame-time p95/p99, CPU load, actual output/world/Iris target dimensions, and CPU/GPU world/post timings. If internal resolution falls to about 25% of output pixels but GPU world time does not fall correspondingly, work may remain at native resolution or be limited by other passes. If GPU world time falls but FPS does not improve, inspect CPU/game-thread limits, frame caps/VSync and post-processing cost. GPU timestamp intervals are useful clues, but they do not replace external profiling and have not been runtime-validated with every Iris/shader-pack version.

## Compatibility and limitations

- **Iris / shader packs:** full render-scale integration is not currently claimed. Shader-pack AA is not post-processed a second time by ModernOPT. Dynamic resolution uses frame cadence rather than the coarse integer FPS counter, and stops adjusting while a shader pack is active because continuous target resizing can cause stutter; the last effective scale is held until shaders are disabled. World-target scaling and shadow optimizations remain experimental and require live validation with the exact Iris and shader-pack versions.
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
- **MSAA:** мультисэмплинг прохода мира с режимами выкл., 2×, 4× и 8×. Максимум ограничен 8×; при ошибке выделения буфера выполняется откат 8× → 4× → 2×.
- **FXAA:** дополнительное сглаживание контуров.
- **Интерфейс в исходном разрешении:** предполагается, что HUD и GUI рисуются в разрешении окна.
- **Клиентские настройки:** доступна отдельная страница настроек в меню модов NeoForge, даже без Sodium. Пресеты меняют одновременно масштаб мира и апскейлер.
- **Монитор производительности и TXT-журнал:** настраиваемые FPS, приблизительный интервал кадров, загрузка CPU, память Java, масштаб мира и апскейлер. Запись добавляется в `config/modernopt-performance.txt`.
- **Отсечение дальних частиц:** необязательное скрытие дальних частиц только на этапе отрисовки. Частицы продолжают обновляться и исчезать как обычно; функция по умолчанию выключена.

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

### Экранная статистика и текстовый журнал

Откройте настройки ModernOPT через кнопку конфигурации мода в списке модов NeoForge — Sodium для этого не нужен. Вкладки позволяют настроить рендеринг, экранную статистику и отсечение частиц. Подсказки описывают ожидаемое влияние на производительность; фактический результат зависит от оборудования и сцены.

Включите **Записывать TXT**, чтобы добавлять периодические измерения в `config/modernopt-performance.txt`. Оверлей показывает FPS Minecraft, приблизительное время между отрисовками HUD, загрузку CPU, heap-память Java, масштаб мира и апскейлер. CSV-бенчмарк в `config/modernopt-benchmarks/` дополнительно записывает загрузку CPU, реальные размеры выходного и внутреннего буфера, долю пикселей, активность шейдерпака Iris и время CPU на проход мира/постобработку. При включённой диагностике HUD также показывает осторожные рекомендации по измерениям CPU, p95 времени кадра и давлению на heap; настройки автоматически не меняются. Это не замеры времени выполнения GPU, температуры или VRAM.

Отсечение частиц — только визуальная проверка дистанции при отрисовке: симуляция частиц не меняется. Функция выключена по умолчанию; она может уменьшить работу по отрисовке сцен с большим количеством частиц, но добавляет небольшую проверку расстояния на каждую частицу.

### Как выяснить, почему 50% дают небольшой прирост FPS

Включите **Profiling** на вкладке динамического разрешения и HUD. Сначала запишите CSV на 30–60 секунд при 100%, затем повторите при 50% в том же мире, в той же точке и с тем же направлением камеры, погодой, настройками шейдерпака, лимитом FPS и VSync. Перед каждым замером дайте сцене прогреться; сделайте по два запуска.

Сравните средний FPS и процентили времени кадра, загрузку CPU, размеры буферов и время CPU на проход мира/постобработку. Если внутренний буфер действительно содержит примерно четверть пикселей, а FPS почти не меняется, вероятно, ограничение не только в количестве пикселей: возможны нагрузка CPU/главного потока, полноразмерные проходы шейдерпака, ограничение FPS/VSync или стоимость апскейлинга. Время CPU — лишь подсказка, а не GPU-таймер; по одному CSV нельзя достоверно назвать конкретный медленный GPU-проход.

## Совместимость и ограничения

- **Iris / шейдер-паки:** полноценная интеграция масштабирования рендера пока не заявляется. ModernOPT не должен повторно сглаживать уже обработанный шейдерпаком кадр. Масштабирование мировых буферов и оптимизации теней остаются экспериментальными и требуют проверки на конкретных версиях Iris и шейдерпака.
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
