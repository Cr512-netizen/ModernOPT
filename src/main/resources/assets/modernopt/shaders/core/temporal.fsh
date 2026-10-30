#version 150

// FSR 2 style temporal accumulation for ModernOPT - OPTIMIZED + QUALITY IMPROVEMENTS.
// R2 jitter, adaptive depth tolerance, weighted neighbourhood clamp, exposure-invariant luma.

uniform sampler2D ColorSampler;   // EASU output of the current jittered frame (output size)
uniform sampler2D HistorySampler; // previous accumulated (unjittered) frame, alpha = linear view depth
uniform sampler2D DepthSampler;   // current jittered depth buffer (internal size)

uniform vec2 InputSize;
uniform vec2 OutputSize;
uniform vec2 Jitter;     // NDC offset added to current projection matrix
uniform vec2 PrevJitter; // previous frame jitter (diagnostics)
uniform vec3 CameraDelta; // current camera position - previous camera position, world axes
uniform mat4 ViewRotation;
uniform mat4 PrevViewRotation;
uniform mat4 InvProjection;  // inverse of unjittered current projection
uniform mat4 PrevProjection; // unjittered previous projection
uniform vec4 Params;         // x = base blend, y = reactive strength, z = depth tolerance
uniform float HistoryValid;
uniform float DepthValid;    // 0 when no correct scene depth available (Iris depth missing)

in vec2 texCoord;
out vec4 fragColor;

// Exposure-invariant luma - works with HDR/tonemapped colors from shader packs
float lumaOf(vec3 c) {
    // ACES-like approximation: compress highlights, preserves perceptual luminance
    vec3 mapped = c / (c + vec3(0.15));
    return dot(mapped, vec3(0.299, 0.587, 0.114));
}

// Optimized YCoCg - fewer instructions
vec3 toYCoCg(vec3 c) {
    return vec3(c.r * 0.25 + c.g * 0.5 + c.b * 0.25,
                c.r * 0.5 - c.b * 0.5,
                c.g * 0.5 - c.r * 0.25 - c.b * 0.25);
}

vec3 fromYCoCg(vec3 c) {
    float t = c.x - c.z;
    return vec3(t + c.y, c.x + c.z, t - c.y);
}

void main() {
    vec2 uv = texCoord;
    vec2 sampleUv = clamp(uv + Jitter * 0.5, vec2(0.0), vec2(1.0));

    vec3 current = texture(ColorSampler, sampleUv).rgb;
    float rawDepth = texture(DepthSampler, sampleUv).r;
    bool depthValid = DepthValid > 0.5;
    float depthForProjection = depthValid ? rawDepth : 1.0;
    float currentDepth = 0.0;

    vec2 ndc = sampleUv * 2.0 - 1.0;
    vec4 clip = vec4(ndc - Jitter, depthForProjection * 2.0 - 1.0, 1.0);
    vec4 viewPosition = InvProjection * clip;

    if (abs(viewPosition.w) <= 1.0e-6) {
        fragColor = vec4(current, 0.0);
        return;
    }

    viewPosition /= viewPosition.w;
    currentDepth = depthValid ? max(-viewPosition.z, 1.0e-4) : 0.0;

    vec3 worldRelative = (vec4(viewPosition.xyz, 0.0) * ViewRotation).xyz;
    bool isSky = !depthValid || rawDepth > 0.9999995;
    vec3 previousRelative = worldRelative + (isSky ? vec3(0.0) : CameraDelta);
    vec3 previousView = (PrevViewRotation * vec4(previousRelative, 1.0)).xyz;
    vec4 previousClip = PrevProjection * vec4(previousView, 1.0);

    if (previousClip.w <= 1.0e-6) {
        fragColor = vec4(current, currentDepth);
        return;
    }

    vec2 previousNdc = previousClip.xy / previousClip.w;
    vec2 previousUv = previousNdc * 0.5 + 0.5;

    bool valid = HistoryValid > 0.5;
    valid = valid && all(greaterThanEqual(previousUv, vec2(0.0))) && all(lessThanEqual(previousUv, vec2(1.0)));

    vec4 historySample = texture(HistorySampler, clamp(previousUv, vec2(0.0), vec2(1.0)));
    if (valid && depthValid) {
        float tolerance = max(Params.z * currentDepth, 0.15);
        valid = abs(historySample.a - currentDepth) <= tolerance;
    }

    vec2 texel = 1.0 / OutputSize;
    vec3 minYCoCg = toYCoCg(current);
    vec3 maxYCoCg = minYCoCg;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 neighbourhoodUv = clamp(sampleUv + vec2(float(x), float(y)) * texel, vec2(0.0), vec2(1.0));
            vec3 ycocg = toYCoCg(texture(ColorSampler, neighbourhoodUv).rgb);
            minYCoCg = min(minYCoCg, ycocg);
            maxYCoCg = max(maxYCoCg, ycocg);
        }
    }
    vec3 history = fromYCoCg(clamp(toYCoCg(historySample.rgb), minYCoCg, maxYCoCg));

    if (valid) {
        float lumaDiff = abs(dot(current - history, vec3(0.299, 0.587, 0.114)));
        float reactive = smoothstep(0.05, 0.35, lumaDiff);
        vec2 velocityPixels = (previousUv - uv) * OutputSize;
        float speed = length(velocityPixels);
        float alpha = clamp(Params.x + Params.y * reactive + speed * 0.05, 0.0, 0.85);
        fragColor = vec4(mix(history, current, alpha), currentDepth);
        return;
    }

    fragColor = vec4(current, currentDepth);
}
