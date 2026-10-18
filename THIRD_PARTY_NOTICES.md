# Third-party notices / Уведомления о сторонних компонентах

ModernOPT includes or adapts components from third-party projects. These components retain their applicable copyright notices and licence terms. The MIT licence in the repository root applies to ModernOPT's own code; it does not replace or override third-party licences.

## AMD FidelityFX Super Resolution 1 (FSR1)

The FSR1 shader implementation and related source material in `src/main/resources/assets/modernopt/shaders/include/fsr1/` are derived from AMD FidelityFX Super Resolution 1.

- Project: https://github.com/GPUOpen-Effects/FidelityFX-FSR
- Copyright: Copyright (c) 2021 Advanced Micro Devices, Inc.
- Licence: MIT
- The applicable copyright and permission notice is retained with the relevant source where supplied.

## RenderScale

Parts of the FSR shader integration structure and helper-shader approach are adapted from RenderScale by Zolo101.

- Project: https://github.com/Zolo101/RenderScale
- Licence: MIT (see the upstream repository's licence file)
- The original project and its copyright/licence notice must be credited when redistributing code derived from it. ModernOPT is an independent project and is not an official RenderScale build or endorsement.

The upstream RenderScale licence notice available to this project was reproduced below. The copyright-holder field in the copied notice was blank in the source available to this repository; it is intentionally not guessed here. Please consult the upstream licence file for the authoritative current notice.

```text
The MIT License (MIT)

Copyright (c) 2024

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

- The public dynamic-scale controller and tests were reviewed for smoothing, stall rejection, bounded updates, and avoiding visible scale pumping while the camera is stationary; ModernOPT's controller is an independent implementation, not copied Java code.

- The Iris 1.21.1 color-space resize fast path is adapted from RenderScale's MIT-licensed compatibility mixin: when only the output dimensions change, it resizes the existing conversion texture rather than recompiling an unchanged color-space shader. The original RenderScale MIT notice above applies.

## Vira — design reference (no source code copied)

- Project: https://www.curseforge.com/minecraft/mc-mods/vira
- Author: mzg
- Published licence: MIT
- Reference use: the public feature description informed the separation of reduced-resolution world rendering from native-resolution HUD/UI, periodic Iris shadow refresh as an opt-in experiment, and frame-time/1% low diagnostics.
- No Vira source files or code fragments have been copied into ModernOPT in this change. This is design attribution, not a claim that ModernOPT contains Vira-derived code. If source code is adopted in a later change, its exact upstream copyright and MIT notice will be added before redistribution.

## Other dependencies and trademarks

Minecraft and NeoForge are build/runtime dependencies and are not included as proprietary assets by this notice. Their names and marks remain the property of their respective owners. See their official terms and licence information for details.

If you identify a missing or inaccurate attribution, please open an issue with the component name, source URL, and relevant file path so it can be reviewed and corrected.
