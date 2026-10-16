#ifdef MODERNOPT_EXPLICIT_GATHER
vec4 renderScaleGatherFp16(vec2 p, int component)
{
    ivec2 size = textureSize(InSampler, 0);
    ivec2 base = ivec2(floor(p * vec2(size) - 0.5));
    ivec2 maximum = size - ivec2(1);
    return vec4(
        texelFetch(InSampler, clamp(base + ivec2(0, 1), ivec2(0), maximum), 0)[component],
        texelFetch(InSampler, clamp(base + ivec2(1, 1), ivec2(0), maximum), 0)[component],
        texelFetch(InSampler, clamp(base + ivec2(1, 0), ivec2(0), maximum), 0)[component],
        texelFetch(InSampler, clamp(base, ivec2(0), maximum), 0)[component]
    );
}
#define RENDERSCALE_GATHER_FP16(p, component) renderScaleGatherFp16(p, component)
#else
#define RENDERSCALE_GATHER_FP16(p, component) textureGather(InSampler, p, component)
#endif
AH4 FsrEasuRH(AH2 p) { return AH4(RENDERSCALE_GATHER_FP16(AF2(p), 0)); }
AH4 FsrEasuGH(AH2 p) { return AH4(RENDERSCALE_GATHER_FP16(AF2(p), 1)); }
AH4 FsrEasuBH(AH2 p) { return AH4(RENDERSCALE_GATHER_FP16(AF2(p), 2)); }
void main()
{
    AU2 outputPixel = AU2(gl_FragCoord.xy);
    vec2 inputSize = vec2(textureSize(InSampler, 0));
    AU4 fsrConst0;
    AU4 fsrConst1;
    AU4 fsrConst2;
    AU4 fsrConst3;
    FsrEasuCon(fsrConst0, fsrConst1, fsrConst2, fsrConst3,
        inputSize.x, inputSize.y, inputSize.x, inputSize.y, OutputSize.x, OutputSize.y);
    AH3 colour;
    FsrEasuH(colour, outputPixel, fsrConst0, fsrConst1, fsrConst2, fsrConst3);
    fragColor = vec4(AF3(colour), 1.0);
}
