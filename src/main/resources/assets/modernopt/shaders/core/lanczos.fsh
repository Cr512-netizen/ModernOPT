#version 150

// Generic Lanczos reconstruction for the spatial upscalers. Params.x is the kernel radius
// (2.0 = Lanczos-2, 3.0 = Lanczos-3). The kernel is evaluated on the source texel grid with
// texelFetch, so it does not depend on the source texture filter.

uniform sampler2D InputSampler;
uniform vec2 InputSize;
uniform vec4 Params;

in vec2 texCoord;
out vec4 fragColor;

float lanczosWeight(float x, float radius) {
    x = abs(x);
    if (x < 1.0e-5) {
        return 1.0;
    }
    if (x >= radius) {
        return 0.0;
    }
    float px = 3.14159265359 * x;
    return radius * sin(px) * sin(px / radius) / (px * px);
}

void main() {
    float radius = max(Params.x, 2.0);
    vec2 source = texCoord * InputSize - 0.5;
    vec2 base = floor(source);
    vec2 fraction = source - base;
    ivec2 sourceSize = ivec2(InputSize);
    ivec2 basePixel = ivec2(base);

    float weightsX[6];
    float weightsY[6];
    for (int i = 0; i < 6; i++) {
        weightsX[i] = lanczosWeight(float(i - 2) - fraction.x, radius);
        weightsY[i] = lanczosWeight(float(i - 2) - fraction.y, radius);
    }

    vec3 sum = vec3(0.0);
    float weightSum = 0.0;
    vec3 minColor = vec3(1.0e20);
    vec3 maxColor = vec3(-1.0e20);

    for (int y = 0; y < 6; y++) {
        float wy = weightsY[y];
        if (abs(wy) < 1.0e-6) {
            continue;
        }
        for (int x = 0; x < 6; x++) {
            float weight = weightsX[x] * wy;
            if (abs(weight) < 1.0e-6) {
                continue;
            }
            ivec2 pixel = clamp(basePixel + ivec2(x - 2, y - 2), ivec2(0), sourceSize - ivec2(1));
            vec3 color = texelFetch(InputSampler, pixel, 0).rgb;
            sum += color * weight;
            weightSum += weight;
            minColor = min(minColor, color);
            maxColor = max(maxColor, color);
        }
    }

    vec3 result;
    if (weightSum <= 1.0e-5 || minColor.x > maxColor.x) {
        result = texelFetch(InputSampler, clamp(basePixel, ivec2(0), sourceSize - ivec2(1)), 0).rgb;
    } else {
        // Clamp the negative lobes to the source neighbourhood to suppress halos.
        result = clamp(sum / weightSum, minColor, maxColor);
    }
    fragColor = vec4(max(result, vec3(0.0)), 1.0);
}
