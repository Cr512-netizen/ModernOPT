#version 150

// MSR 2.0 Temporal Pass - fragment fallback for GPUs without GL 4.3 compute.
// Variance clipping in YCoCg, camera-reconstructed reprojection.
//
// Outputs are MRT: attachment 0 is the R11G11B10F colour history, attachment 1 is the
// R16UI packed-depth history. The integer attachment MUST be written through a uvec4 and
// the integer history texture MUST be sampled through a usampler2D - sampling an R16UI
// texture with a normal sampler2D is undefined and was one of the causes of the black frame.

uniform sampler2D CurrentColor;   // current frame low-res colour
uniform sampler2D CurrentDepth;   // current depth buffer, source resolution
uniform sampler2D HistoryColor;   // previous frame history colour
uniform usampler2D HistoryDepth;  // previous frame packed depth (R16UI)

uniform vec2 InputSize;           // low-res input size
uniform vec2 Jitter;              // current frame jitter (MSR: always zero)
uniform float VarianceScale;      // variance clipping multiplier
uniform float DepthThreshold;     // disocclusion depth threshold
uniform float HistoryWeight;      // base history blend weight
uniform vec3 CameraDelta;         // current camera pos - previous camera pos, world axes
uniform mat4 ViewRotation;        // current view rotation (world -> view)
uniform mat4 PrevViewRotation;    // previous view rotation
uniform mat4 InvProjection;       // inverse of the current projection
uniform mat4 PrevProjection;      // previous projection
uniform float HistoryValid;       // 1.0 when the history contains a valid frame
uniform float DepthValid;         // 1.0 when a scene depth buffer is available

in vec2 texCoord;
out vec4 fragColor;               // RGB = temporal colour
out uvec4 fragDepth;              // R = packed current depth

float lumaOf(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void computeLumaRange(vec2 uv, vec2 texel,
                      out float lumaCenter, out float lumaMin, out float lumaMax) {
    lumaCenter = lumaOf(texture(CurrentColor, uv).rgb);
    lumaMin = lumaCenter;
    lumaMax = lumaCenter;
    float l;
    l = lumaOf(texture(CurrentColor, uv + vec2(-texel.x, 0.0)).rgb); lumaMin = min(lumaMin, l); lumaMax = max(lumaMax, l);
    l = lumaOf(texture(CurrentColor, uv + vec2( texel.x, 0.0)).rgb); lumaMin = min(lumaMin, l); lumaMax = max(lumaMax, l);
    l = lumaOf(texture(CurrentColor, uv + vec2(0.0, -texel.y)).rgb); lumaMin = min(lumaMin, l); lumaMax = max(lumaMax, l);
    l = lumaOf(texture(CurrentColor, uv + vec2(0.0,  texel.y)).rgb); lumaMin = min(lumaMin, l); lumaMax = max(lumaMax, l);
}

void main() {
    vec2 uv = texCoord;
    vec2 currentUv = clamp(uv + Jitter * 0.5, vec2(0.0), vec2(1.0));

    vec3 currentColor = texture(CurrentColor, currentUv).rgb;
    float currentDepth = texture(CurrentDepth, currentUv).r;

    bool valid = HistoryValid > 0.5;
    bool depthValid = DepthValid > 0.5;

    vec2 historyUv = uv;
    float historyDepth = 0.0;
    vec3 historyColor = vec3(0.0);

    if (valid && depthValid) {
        vec2 ndc = currentUv * 2.0 - 1.0;
        vec4 clip = vec4(ndc, currentDepth * 2.0 - 1.0, 1.0);
        vec4 viewPosition = InvProjection * clip;

        if (abs(viewPosition.w) > 1.0e-6) {
            viewPosition /= viewPosition.w;

            // vec4 * mat4 applies the transpose, i.e. view space -> world relative.
            vec3 worldRelative = (vec4(viewPosition.xyz, 0.0) * ViewRotation).xyz;
            bool isSky = currentDepth > 0.9999995;
            vec3 previousRelative = worldRelative + (isSky ? vec3(0.0) : CameraDelta);

            vec3 previousView = (PrevViewRotation * vec4(previousRelative, 1.0)).xyz;
            vec4 previousClip = PrevProjection * vec4(previousView, 1.0);

            if (previousClip.w > 1.0e-6) {
                vec2 previousNdc = previousClip.xy / previousClip.w;
                historyUv = previousNdc * 0.5 + 0.5;
                valid = valid
                        && all(greaterThanEqual(historyUv, vec2(0.0)))
                        && all(lessThanEqual(historyUv, vec2(1.0)));
            } else {
                valid = false;
            }
        } else {
            valid = false;
        }
    } else {
        valid = false;
    }

    if (valid) {
        historyColor = texture(HistoryColor, historyUv).rgb;
        historyDepth = float(texture(HistoryDepth, historyUv).r) / 65535.0;

        float tolerance = max(DepthThreshold * currentDepth * currentDepth, 0.02);
        valid = abs(historyDepth - currentDepth) <= tolerance;
    }

    float lumaCenter;
    float lumaMin;
    float lumaMax;
    computeLumaRange(currentUv, 1.0 / InputSize, lumaCenter, lumaMin, lumaMax);
    float historyLuma = lumaOf(historyColor);
    // VarianceScale widens/narrows the accepted luma window (same knob as the compute path).
    float rangePad = 0.05 * VarianceScale;
    bool withinRange = historyLuma >= lumaMin - rangePad && historyLuma <= lumaMax + rangePad;

    float historyBlend = HistoryWeight;
    if (!valid || !withinRange) {
        historyBlend = 0.0;
    }

    vec3 outputColor = mix(currentColor, historyColor, historyBlend);

    fragColor = vec4(outputColor, 1.0);
    fragDepth = uvec4(uint(clamp(currentDepth * 65535.0, 0.0, 65535.0)), 0u, 0u, 0u);
}
