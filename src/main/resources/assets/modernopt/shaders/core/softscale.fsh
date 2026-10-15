#version 150
uniform sampler2D InSampler;
in vec2 texCoord;
out vec4 fragColor;

// Lightweight SoftScale: phase-aware 3x3 tent reconstruction.
// Uses 9 texture reads and simple arithmetic instead of a 4x4 Gaussian
// kernel with exp() calls. This keeps a softer result than nearest-neighbor
// while reducing the cost of the upscaling pass.
void main() {
    ivec2 size = textureSize(InSampler, 0);
    vec2 sourcePos = texCoord * vec2(size) - vec2(0.5);
    ivec2 base = ivec2(floor(sourcePos));
    vec2 phase = fract(sourcePos);
    ivec2 maximum = size - ivec2(1);

    vec4 sum = vec4(0.0);
    float totalWeight = 0.0;

    for (int y = -1; y <= 1; ++y) {
        float dy = abs(float(y) - phase.y);
        float wy = max(0.0, 1.5 - dy);
        for (int x = -1; x <= 1; ++x) {
            float dx = abs(float(x) - phase.x);
            float wx = max(0.0, 1.5 - dx);
            float weight = wx * wy;
            ivec2 samplePos = clamp(base + ivec2(x, y), ivec2(0), maximum);
            sum += texelFetch(InSampler, samplePos, 0) * weight;
            totalWeight += weight;
        }
    }

    fragColor = sum / max(totalWeight, 0.0001);
}
