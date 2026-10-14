#version 150
uniform sampler2D InSampler;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    ivec2 size = textureSize(InSampler, 0);
    vec2 pixel = texCoord * vec2(size) - vec2(0.5);
    ivec2 base = ivec2(floor(pixel));
    vec2 fraction = fract(pixel);
    ivec2 maximum = size - ivec2(1);
    vec4 topLeft = texelFetch(InSampler, clamp(base, ivec2(0), maximum), 0);
    vec4 topRight = texelFetch(InSampler, clamp(base + ivec2(1, 0), ivec2(0), maximum), 0);
    vec4 bottomLeft = texelFetch(InSampler, clamp(base + ivec2(0, 1), ivec2(0), maximum), 0);
    vec4 bottomRight = texelFetch(InSampler, clamp(base + ivec2(1, 1), ivec2(0), maximum), 0);
    fragColor = mix(mix(topLeft, topRight, fraction.x),
                    mix(bottomLeft, bottomRight, fraction.x), fraction.y);
}
