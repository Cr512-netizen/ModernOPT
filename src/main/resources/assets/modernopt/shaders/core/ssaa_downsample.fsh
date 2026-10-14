#version 150
uniform sampler2D InSampler;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    ivec2 size = textureSize(InSampler, 0);
    ivec2 base = ivec2(gl_FragCoord.xy) * 2;
    ivec2 maximum = size - ivec2(1);
    vec4 a = texelFetch(InSampler, clamp(base, ivec2(0), maximum), 0);
    vec4 b = texelFetch(InSampler, clamp(base + ivec2(1, 0), ivec2(0), maximum), 0);
    vec4 c = texelFetch(InSampler, clamp(base + ivec2(0, 1), ivec2(0), maximum), 0);
    vec4 d = texelFetch(InSampler, clamp(base + ivec2(1, 1), ivec2(0), maximum), 0);
    fragColor = (a + b + c + d) * 0.25;
}
