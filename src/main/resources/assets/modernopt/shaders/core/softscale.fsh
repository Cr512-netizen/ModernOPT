#version 150
uniform sampler2D InSampler;
in vec2 texCoord;
out vec4 fragColor;

// SoftScale: a lightweight 3x3 Gaussian reconstruction filter.
// It intentionally softens fine detail instead of applying sharpening.
void main() {
    ivec2 size = textureSize(InSampler, 0);
    ivec2 center = ivec2(texCoord * vec2(size));
    ivec2 maximum = size - ivec2(1);

    vec4 sum = vec4(0.0);
    sum += texelFetch(InSampler, clamp(center + ivec2(-1, -1), ivec2(0), maximum), 0) * 1.0;
    sum += texelFetch(InSampler, clamp(center + ivec2( 0, -1), ivec2(0), maximum), 0) * 2.0;
    sum += texelFetch(InSampler, clamp(center + ivec2( 1, -1), ivec2(0), maximum), 0) * 1.0;
    sum += texelFetch(InSampler, clamp(center + ivec2(-1,  0), ivec2(0), maximum), 0) * 2.0;
    sum += texelFetch(InSampler, clamp(center,                 ivec2(0), maximum), 0) * 4.0;
    sum += texelFetch(InSampler, clamp(center + ivec2( 1,  0), ivec2(0), maximum), 0) * 2.0;
    sum += texelFetch(InSampler, clamp(center + ivec2(-1,  1), ivec2(0), maximum), 0) * 1.0;
    sum += texelFetch(InSampler, clamp(center + ivec2( 0,  1), ivec2(0), maximum), 0) * 2.0;
    sum += texelFetch(InSampler, clamp(center + ivec2( 1,  1), ivec2(0), maximum), 0) * 1.0;
    fragColor = sum / 16.0;
}
