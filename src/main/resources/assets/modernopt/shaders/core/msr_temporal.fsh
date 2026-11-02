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

// YCoCg conversion - luminance + chroma, better for variance analysis
vec3 rgbToYCoCg(vec3 rgb) {
    return vec3(
        rgb.r * 0.25 + rgb.g * 0.5 + rgb.b * 0.25,  // Y (luma)
        rgb.r * 0.5 - rgb.b * 0.5,                   // Co (orange-blue)
        rgb.g * 0.5 - rgb.r * 0.25 - rgb.b * 0.25    // Cg (green-purple)
    );
}

vec3 yCoCgToRgb(vec3 ycocg) {
    float t = ycocg.x - ycocg.z;
    return vec3(
        t + ycocg.y,      // R
        ycocg.x + ycocg.z, // G
        t - ycocg.y       // B
    );
}

// Depth is already linear 0..1 from texture sampler
// (no unpack needed)

// 3x3 neighbourhood variance in YCoCg space
// Returns: meanYCoCg, varianceYCoCg, and centerYCoCg (center pixel in YCoCg)
void computeVariance(vec2 uv, vec2 texel,
                     out vec3 meanYCoCg, out vec3 varianceYCoCg, out vec3 centerYCoCg) {
    meanYCoCg = vec3(0.0);
    vec3 meanSq = vec3(0.0);
    int count = 0;

    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 sampleUv = uv + vec2(float(x), float(y)) * texel;
            // Clamp to valid range
            sampleUv = clamp(sampleUv, vec2(0.0), vec2(1.0));
            vec3 color = texture(HistoryColor, sampleUv).rgb;
            vec3 ycocg = rgbToYCoCg(color);

            // Capture center pixel (x=0, y=0)
            if (x == 0 && y == 0) {
                centerYCoCg = ycocg;
            }

            meanYCoCg += ycocg;
            meanSq += ycocg * ycocg;
            count++;
        }
    }

    meanYCoCg /= float(count);
    meanSq /= float(count);
    varianceYCoCg = max(vec3(0.0), meanSq - meanYCoCg * meanYCoCg);
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

    // Compute local variance in 3x3 neighbourhood of the CURRENT frame - returns center YCoCg
    // to avoid recomputing it. Used for variance clipping of the history pixel.
    vec2 texel = 1.0 / InputSize;
    vec3 meanYCoCg;
    vec3 varianceYCoCg;
    vec3 currentCenterYCoCg;
    computeVariance(currentUv, texel, meanYCoCg, varianceYCoCg, currentCenterYCoCg);

    vec3 currentYCoCg;
    vec3 historyYCoCg;
    currentYCoCg = currentCenterYCoCg;
    historyYCoCg = rgbToYCoCg(historyColor);

    // Variance clipping: reject history if outside confidence interval
    // Confidence interval = mean ± VarianceScale * stdDev
    vec3 stdDev;
    vec3 lowerBound;
    vec3 upperBound;
    stdDev = sqrt(max(vec3(0.0), varianceYCoCg));
    lowerBound = meanYCoCg - VarianceScale * stdDev;
    upperBound = meanYCoCg + VarianceScale * stdDev;

    bool withinVariance;
    withinVariance = all(greaterThanEqual(historyYCoCg, lowerBound))
                 && all(lessThanEqual(historyYCoCg, upperBound));

    // Motion detection: high variance = motion
    float maxVariance;
    bool isStatic;
    maxVariance = max(max(varianceYCoCg.x, varianceYCoCg.y), varianceYCoCg.z);
    isStatic = maxVariance < 0.001;  // Very low variance = static

    // Adaptive blending
    float historyBlend;
    historyBlend = HistoryWeight;

    if (!valid || !withinVariance) {
        // History rejected - use current frame only
        historyBlend = 0.0;
    } else if (isStatic) {
        // Static scene - increase history weight for stability
        historyBlend = min(HistoryWeight * 1.5, 0.95);
    }

    // Blend in YCoCg space for better color stability
    vec3 blendedYCoCg;
    vec3 outputColor;
    blendedYCoCg = mix(currentYCoCg, historyYCoCg, historyBlend);
    outputColor = yCoCgToRgb(blendedYCoCg);

    // Output: RGB = temporal color, A = current depth (for next frame's disocclusion)
    fragColor = vec4(outputColor, currentDepth);
}