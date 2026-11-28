#version 150

// Generic Lanczos reconstruction for the spatial upscalers. Params.x is the kernel radius
// (2.0 = Lanczos-2, 3.0 = Lanczos-3). The polyphase weights come from the same 129x12 table as
// the MSR2 compute path, so both use an identical kernel and no transcendental functions run per
// pixel.
//
// Luma is reconstructed with the signed Lanczos kernel. Chroma uses a plain bilinear fetch:
// chroma detail is low-frequency, and a signed kernel on colour-difference channels is what
// produces coloured halos on high-contrast edges. The anti-ringing clamp limits overshoot to a
// small budget, so real edge contrast survives but large halos do not.
uniform sampler2D InputSampler;
uniform sampler2D DepthSampler;
uniform sampler2D LanczosTable;
uniform vec2 InputSize;
uniform vec4 Params;
uniform float DepthValid;

in vec2 texCoord;
out vec4 fragColor;

vec3 rgbToYCoCg(vec3 rgb) {
    return vec3(
        rgb.r * 0.25 + rgb.g * 0.5 + rgb.b * 0.25,
        rgb.r * 0.5 - rgb.b * 0.5,
        rgb.g * 0.5 - rgb.r * 0.25 - rgb.b * 0.25
    );
}

vec3 yCoCgToRgb(vec3 ycocg) {
    float t = ycocg.x - ycocg.z;
    return vec3(t + ycocg.y, ycocg.x + ycocg.z, t - ycocg.y);
}

vec2 sampleBilinearChroma(vec2 uv) {
    return rgbToYCoCg(texture(InputSampler, uv).rgb).yz;
}

void main() {
    float radius = max(Params.x, 2.0);
    int layer = radius >= 2.5 ? 1 : 0;
    vec2 source = texCoord * InputSize - 0.5;
    vec2 base = floor(source);
    vec2 fraction = clamp(source - base, vec2(0.0), vec2(1.0));
    int phaseX = int(fraction.x * 128.0 + 0.5);
    int phaseY = int(fraction.y * 128.0 + 0.5);
    ivec2 sourceSize = ivec2(InputSize);
    ivec2 basePixel = ivec2(base);

    float weightsX[6];
    float weightsY[6];
    for (int i = 0; i < 6; i++) {
        weightsX[i] = texelFetch(LanczosTable, ivec2(phaseX, i + layer * 6), 0).r;
        weightsY[i] = texelFetch(LanczosTable, ivec2(phaseY, i + layer * 6), 0).r;
    }

    float centerDepth = texture(DepthSampler, texCoord).r;
    float depthTolerance = max(0.0025, centerDepth * 0.02);
    bool useDepth = DepthValid > 0.5;
    if (useDepth) {
        // A four-neighbour depth check decides whether this pixel is near a silhouette.
        // Fetching depth for all 36 colour taps on every pixel costs far more than the filter
        // itself; flat terrain does not need the weights at all.
        ivec2 p = basePixel;
        float d0 = texelFetch(DepthSampler, clamp(p + ivec2(-1, 0), ivec2(0), sourceSize - ivec2(1)), 0).r;
        float d1 = texelFetch(DepthSampler, clamp(p + ivec2(1, 0), ivec2(0), sourceSize - ivec2(1)), 0).r;
        float d2 = texelFetch(DepthSampler, clamp(p + ivec2(0, -1), ivec2(0), sourceSize - ivec2(1)), 0).r;
        float d3 = texelFetch(DepthSampler, clamp(p + ivec2(0, 1), ivec2(0), sourceSize - ivec2(1)), 0).r;
        float minDepth = min(centerDepth, min(min(d0, d1), min(d2, d3)));
        float maxDepth = max(centerDepth, max(max(d0, d1), max(d2, d3)));
        useDepth = maxDepth - minDepth > depthTolerance;
    }

    float lumaSum = 0.0;
    float lumaWeight = 0.0;
    float lumaMin = 1.0e20;
    float lumaMax = -1.0e20;

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

            if (useDepth) {
                float tapDepth = texelFetch(DepthSampler, pixel, 0).r;
                float depthWeight = 1.0 - smoothstep(depthTolerance, depthTolerance * 2.0,
                        abs(tapDepth - centerDepth));
                if (depthWeight <= 0.05) {
                    continue;
                }
                weight *= depthWeight * depthWeight;
            }

            float luma = dot(color, vec3(0.25, 0.5, 0.25));
            lumaSum += luma * weight;
            lumaWeight += weight;
            lumaMin = min(lumaMin, luma);
            lumaMax = max(lumaMax, luma);
        }
    }

    ivec2 centerPixel = clamp(basePixel, ivec2(0), sourceSize - ivec2(1));
    vec3 center = texelFetch(InputSampler, centerPixel, 0).rgb;
    vec3 result;
    if (lumaWeight <= 1.0e-5) {
        result = center;
    } else {
        float luma = lumaSum / lumaWeight;
        float lumaClamped = clamp(luma, lumaMin, lumaMax);
        float lumaContrast = max(lumaMax - lumaMin, 1.0e-4);
        float budget = min(0.010, max(0.0015, lumaContrast * 0.04));
        luma = lumaClamped + clamp(luma - lumaClamped, -budget, budget);
        result = yCoCgToRgb(vec3(luma, sampleBilinearChroma(texCoord)));
    }
    fragColor = vec4(max(result, vec3(0.0)), 1.0);
}
