#version 420
#if defined(GL_AMD_gpu_shader_half_float)
#extension GL_AMD_gpu_shader_half_float : require
#elif defined(GL_NV_gpu_shader5)
#extension GL_NV_gpu_shader5 : require
#else
#error ModernOPT FP16 requires GL_AMD_gpu_shader_half_float or GL_NV_gpu_shader5
#endif
#define A_GPU 1
#define A_GLSL 1
#define A_HALF 1
#define A_SKIP_EXT 1
#define FSR_EASU_H 1
#define FSR_COLOUR AH3
#define FSR_GATHER AH4
#define FSR_EASU_R FsrEasuRH
#define FSR_EASU_G FsrEasuGH
#define FSR_EASU_B FsrEasuBH
#define FSR_EASU FsrEasuH
#define MODERNOPT_EXPLICIT_GATHER 1
#moj_import <modernopt:fsr1/ffx_a.glsl>
#moj_import <modernopt:fsr1/ffx_fsr1.glsl>
uniform sampler2D InSampler;
uniform vec2 OutputSize;
out vec4 fragColor;
#moj_import <modernopt:fsr1/fsr_easu_fp16.glsl>
