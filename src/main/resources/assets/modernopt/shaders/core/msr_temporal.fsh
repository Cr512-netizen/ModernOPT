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

// Unpack R16_UINT depth to linear 0..1
float unpackDepth(uint packed) {
    return float(packed) / 65535.0;
}

// 3x3 neighbourhood variance in YCoCg space
void computeVariance(vec2 uv, vec2 texel, 
                     out vec3 meanYCoCg, out vec3 varianceYCoCg) {
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
    uint currentDepthPacked = texture(CurrentDepth, currentUv).r * 65535.0;
    float currentDepth = unpackDepth(currentDepthPacked);
    
    // Sample history
    vec3 historyColor = texture(HistoryColor, uv).rgb;
    uint historyDepthPacked = texture(HistoryDepth, uv).r * 65535.0;
    float historyDepth = unpackDepth(historyDepthPacked);
    
    // Convert to YCoCg for variance analysis
    vec3 currentYCoCg = rgbToYCoCg(currentColor);
    vec3 historyYCoCg = rgbToYCoCg(historyColor);
    
    // Compute local variance in 3x3 neighbourhood
    vec2 texel = 1.0 / InputSize;  // texel size in low-res space
    vec3 meanYCoCg, varianceYCoCg;
    computeVariance(uv, texel, meanYCoCg, varianceYCoCg);
    
    // Variance clipping: reject history if outside confidence interval
    // Confidence interval = mean ± VarianceScale * stdDev
    vec3 stdDev = sqrt(max(vec3(0.0), varianceYCoCg));
    vec3 lowerBound = meanYCoCg - VarianceScale * stdDev;
    vec3 upperBound = meanYCoCg + VarianceScale * stdDev;
    
    // Check if history color is within confidence interval
    bool withinVariance = all(greaterThanEqual(historyYCoCg, lowerBound)) && 
                          all(lessThanEqual(historyYCoCg, upperBound));
    
    // Depth-based disocclusion check
    float depthDiff = abs(historyDepth - currentDepth);
    bool depthOk = depthDiff < DepthThreshold;
    
    // Motion detection: high variance = motion
    float maxVariance = max(max(varianceYCoCg.x, varianceYCoCg.y), varianceYCoCg.z);
    bool isStatic = maxVariance < 0.001;  // Very low variance = static
    
    // Adaptive blending
    float historyBlend = HistoryWeight;
    
    if (!withinVariance || !depthOk) {
        // History rejected - use current frame only
        historyBlend = 0.0;
    } else if (isStatic) {
        // Static scene - increase history weight for stability
        historyBlend = min(HistoryWeight * 1.5, 0.95);
    }
    
    // Blend in YCoCg space for better color stability
    vec3 blendedYCoCg = mix(currentYCoCg, historyYCoCg, historyBlend);
    vec3 outputColor = yCoCgToRgb(blendedYCoCg);
    
    // Output: RGB = color, A = current depth (for next frame's disocclusion)
    fragColor = vec4(outputColor, currentDepth);
}