#version 420
#define A_GPU 1
#define A_GLSL 1
#define FSR_RCAS_F 1
#define FSR_COLOUR AF3
#define FSR_SCALAR AF1
#define FSR_LOAD AF4
#define FSR_POSITION ASU2
#define FSR_RCAS_LOAD FsrRcasLoadF
#define FSR_RCAS_INPUT FsrRcasInputF
#define FSR_RCAS FsrRcasF
#moj_import <modernopt:fsr1/ffx_a.glsl>
#moj_import <modernopt:fsr1/ffx_fsr1.glsl>
uniform sampler2D InSampler;
out vec4 fragColor;
#moj_import <modernopt:fsr1/fsr_rcas.glsl>
