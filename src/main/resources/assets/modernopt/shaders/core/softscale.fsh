#version 150
uniform sampler2D InSampler;
in vec2 texCoord;
out vec4 fragColor;

// SoftScale: phase-aware 4x4 Gaussian reconstruction for spatial upscaling.
// Unlike a fixed blur kernel, weights follow the fractional source-pixel position.
void main() {
    ivec2 size = textureSize(InSampler, 0);
    vec2 sourcePos = texCoord * vec2(size) - vec2(0.5);
    ivec2 base = ivec2(floor(sourcePos));
    vec2 phase = fract(sourcePos);
    ivec2 maximum = size - ivec2(1);
    vec4 sum = vec4(0.0);
    float totalWeight = 0.0;

    for (int y = -1; y <= 2; ++y) {
        float dy = float(y) - phase.y;
        float wy = exp(-1.5 * dy * dy);
        for (int x = -1; x <= 2; ++x) {
            float dx = float(x) - phase.x;
            float weight = wy * exp(-1.5 * dx * dx);
            ivec2 samplePos = clamp(base + ivec2(x, y), ivec2(0), maximum);
            sum += texelFetch(InSampler, samplePos, 0) * weight;
            totalWeight += weight;
        }
    }
    fragColor = sum / max(totalWeight, 0.0001);
}
