#version 450
#extension GL_NV_gpu_shader5 : require
#define A_GPU 1
#define A_GLSL 1
#define A_HALF 1
#define FSR_RCAS_H 1
#define FSR_COLOUR AH3
#define FSR_SCALAR AH1
#define FSR_LOAD AH4
#define FSR_POSITION ASU2
#define FSR_RCAS_LOAD FsrRcasLoadH
#define FSR_RCAS_INPUT FsrRcasInputH
#define FSR_RCAS FsrRcasH
#moj_import <modernopt:fsr1/ffx_a.glsl>
#moj_import <modernopt:fsr1/ffx_fsr1.glsl>
uniform sampler2D InSampler;
out vec4 fragColor;
#moj_import <modernopt:fsr1/fsr_rcas.glsl>
