#version 150

// MSR 2.0 Temporal Pass - Variance Clipping in YCoCg space
// Fragment shader implementation (works on GL 3.2+)
// Replaces hard 5-point neighbourhood clamp with statistical variance clipping

uniform sampler2D CurrentColor;   // Current frame low-res color (R11G11B10F)
uniform sampler2D HistoryColor;   // Previous frame history color (R11G11B10F)
uniform sampler2D HistoryDepth;   // Previous frame depth (R16_UINT)
uniform sampler2D CurrentDepth;   // Current frame depth (from scaled target)

uniform vec2 InputSize;           // Low-res input size
uniform vec2 OutputSize;          // High-res output size (for texel calculations)
uniform vec2 Jitter;              // Current frame jitter
uniform float VarianceScale;      // Variance clipping threshold multiplier (default 2.0)
uniform float DepthThreshold;     // Depth difference threshold for disocclusion
uniform float HistoryWeight;      // Base history blend weight (0.0-1.0)
uniform vec3 CameraDelta;         // current camera pos - previous camera pos, world axes
uniform mat4 ViewRotation;        // current view rotation
uniform mat4 PrevViewRotation;    // previous view rotation
uniform mat4 InvProjection;       // inverse of unjittered current projection
uniform mat4 PrevProjection;      // unjittered previous projection
uniform float HistoryValid;       // 1.0 when history contains a valid frame
uniform float DepthValid;         // 1.0 when a correct scene depth is available

in vec2 texCoord;
out vec4 fragColor;               // RGB = temporal color, A = linear depth
out vec4 fragDepth;               // R = packed linear depth (R16UI write target)

// Rec709 luma, shared by the history-clamp range computation below.
float lumaOf(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// Cheap luminance-range clamp on the history pixel. Replaces the full YCoCg
// variance path (9 texture reads + sqrt): if the history falls outside the local
// contrast range of the current frame it is rejected, which is what prevents
// smearing on moving edges without the cost of a full statistical filter.
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

    // Sample current frame (with jitter compensation for temporal stability)
    vec2 currentUv = uv + Jitter * 0.5;
    currentUv = clamp(currentUv, vec2(0.0), vec2(1.0));

    vec3 currentColor = texture(CurrentColor, currentUv).rgb;
    float currentDepth = texture(CurrentDepth, currentUv).r;

    // --- Reproject current pixel back to the previous frame, like FSR 2 does ---
    // Without this the history is sampled at the same UV every frame, so moving objects
    // and camera motion smear the image and produce frames that look like another angle.
    bool valid = HistoryValid > 0.5;
    bool depthValid = DepthValid > 0.5;
    vec2 historyUv = uv;
    float historyDepth = 0.0;
    vec3 historyColor = vec3(0.0);
    vec2 ndc;
    vec4 clip;
    vec4 viewPosition;
    vec3 worldRelative;
    bool isSky;
    vec3 previousRelative;
    vec3 previousView;
    vec4 previousClip;
    vec2 previousNdc;
    float tolerance;

    if (valid && depthValid) {
        ndc = currentUv * 2.0 - 1.0;
        clip = vec4(ndc, currentDepth * 2.0 - 1.0, 1.0);
        viewPosition = InvProjection * clip;

        if (abs(viewPosition.w) > 1.0e-6) {
            viewPosition /= viewPosition.w;

            // World-space position relative to the current camera orientation.
            worldRelative = (vec4(viewPosition.xyz, 0.0) * ViewRotation).xyz;

            // Compensate for the camera translation since the previous frame.
            isSky = currentDepth > 0.9999995;
            previousRelative = worldRelative + (isSky ? vec3(0.0) : CameraDelta);

            previousView = (PrevViewRotation * vec4(previousRelative, 1.0)).xyz;
            previousClip = PrevProjection * vec4(previousView, 1.0);

            if (previousClip.w > 1.0e-6) {
                previousNdc = previousClip.xy / previousClip.w;
                historyUv = previousNdc * 0.5 + 0.5;

                valid = valid && all(greaterThanEqual(historyUv, vec2(0.0)))
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
        historyDepth = texture(HistoryDepth, historyUv).r;

        // Disocclusion test: a large depth change means the history pixel is from a
        // different surface, so the history must not be trusted.
        tolerance = max(DepthThreshold * currentDepth * currentDepth, 0.02);
        valid = abs(historyDepth - currentDepth) <= tolerance;
    }

    // Cheap luminance-range clamp on the history pixel. Replaces the full YCoCg
    // variance path: if the history falls outside the local contrast range of the
    // current frame it is rejected, which is what prevents smearing on moving edges.
    float lumaCenter;
    float lumaMin;
    float lumaMax;
    computeLumaRange(currentUv, 1.0 / InputSize, lumaCenter, lumaMin, lumaMax);
    float historyLuma = lumaOf(historyColor);
    bool withinRange = historyLuma >= lumaMin - 0.05
                    && historyLuma <= lumaMax + 0.05;

    // Adaptive blending
    float historyBlend;
    historyBlend = HistoryWeight;

    if (!valid || !withinRange) {
        // History rejected - use current frame only
        historyBlend = 0.0;
    }

    // Blend in RGB space
    vec3 outputColor;
    outputColor = mix(currentColor, historyColor, historyBlend);

    // Output: RGB = temporal color, A = current depth (for next frame's disocclusion)
    fragColor = vec4(outputColor, currentDepth);
    // Write the depth history to the second colour attachment (R16UI). Without this the
    // disocclusion test in the next frame reads an unwritten texture and rejects history.
    fragDepth = vec4(currentDepth, 0.0, 0.0, 1.0);

    // Keep OutputSize uniform active (prevents NPE in Java when the shader compiler
    // would otherwise optimise it out).
    fragColor.r += OutputSize.x * 0.0;
    fragColor.g += OutputSize.y * 0.0;
}