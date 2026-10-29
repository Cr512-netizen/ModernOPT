#version 150

// MSR 2.0 Spatial Pass - Fused EASU + RCAS + Edge-Aware Interpolation
// Single fragment shader pass for TBDR compatibility (iGPU, mobile)
// Input: Temporal pass output (high-res), depth buffer (high-res)
// Output: Final upscaled + sharpened image

uniform sampler2D TemporalColor;    // Output from temporal pass (high-res)
uniform sampler2D DepthTexture;     // High-res depth buffer

uniform vec2 InputSize;             // Temporal output size (high-res)
uniform vec2 OutputSize;            // Screen size (native res)
uniform float Sharpness;            // CAS sharpness 0.0-1.0
uniform float EdgeAwareStrength;    // Edge-aware interpolation strength 0.0-1.0
uniform float DepthEdgeThreshold;   // Depth gradient threshold for edges

in vec2 texCoord;
out vec4 fragColor;

// EASU constants (from AMD FidelityFX)
const float EASU_SHARPNESS_DEFAULT = 0.0;

// Luma calculation (rec709)
float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// Edge-aware interpolation weight based on depth gradient
float edgeAwareWeight(vec2 uv, vec2 texel) {
    // Sample depth in cross pattern
    float dC = texture(DepthTexture, uv).r;
    float dL = texture(DepthTexture, uv + vec2(-texel.x, 0.0)).r;
    float dR = texture(DepthTexture, uv + vec2(texel.x, 0.0)).r;
    float dU = texture(DepthTexture, uv + vec2(0.0, -texel.y)).r;
    float dD = texture(DepthTexture, uv + vec2(0.0, texel.y)).r;
    
    // Depth gradients
    float gradX = abs(dR - dL);
    float gradY = abs(dD - dU);
    float maxGrad = max(gradX, gradY);
    
    // Edge = high depth gradient
    float edge = smoothstep(DepthEdgeThreshold, DepthEdgeThreshold * 4.0, maxGrad);
    
    // Return interpolation bias: 1.0 = interpolate along edge, 0.0 = standard
    return edge * EdgeAwareStrength;
}

// Simplified EASU - edge-adaptive spatial upsampling
vec3 easuSample(vec2 uv, vec2 texel) {
    // 5-tap cross pattern for edge direction
    vec3 c = texture(TemporalColor, uv).rgb;
    vec3 l = texture(TemporalColor, uv + vec2(-texel.x, 0.0)).rgb;
    vec3 r = texture(TemporalColor, uv + vec2(texel.x, 0.0)).rgb;
    vec3 u = texture(TemporalColor, uv + vec2(0.0, -texel.y)).rgb;
    vec3 d = texture(TemporalColor, uv + vec2(0.0, texel.y)).rgb;
    
    float lC = luma(c);
    float lL = luma(l);
    float lR = luma(r);
    float lU = luma(u);
    float lD = luma(d);
    
    // Edge directions
    float horz = abs(lL - lR);
    float vert = abs(lU - lD);
    
    // Interpolation weights based on edge direction
    float wH = 1.0 / max(horz, 0.001);
    float wV = 1.0 / max(vert, 0.001);
    float sumW = wH + wV;
    wH /= sumW;
    wV /= sumW;
    
    // Edge-aware weight
    float ew = edgeAwareWeight(uv, texel);
    
    // Blend horizontal/vertical based on edge direction
    vec3 result = wH * (l + r) * 0.5 + wV * (u + d) * 0.5;
    
    // Apply edge-aware bias
    result = mix(result, c, ew);
    
    return result;
}

// CAS (Contrast Adaptive Sharpening) - simplified from AMD FidelityFX
vec3 casSharpen(vec2 uv, vec2 texel, vec3 color) {
    if (Sharpness <= 0.0) return color;
    
    // 5-tap luma
    float lC = luma(color);
    float lL = luma(texture(TemporalColor, uv + vec2(-texel.x, 0.0)).rgb);
    float lR = luma(texture(TemporalColor, uv + vec2(texel.x, 0.0)).rgb);
    float lU = luma(texture(TemporalColor, uv + vec2(0.0, -texel.y)).rgb);
    float lD = luma(texture(TemporalColor, uv + vec2(0.0, texel.y)).rgb);
    
    // Local contrast
    float lMin = min(min(min(lL, lR), min(lU, lD)), lC);
    float lMax = max(max(max(lL, lR), max(lU, lD)), lC);
    float contrast = lMax - lMin;
    
    // Edge detection
    float edgeH = abs(lL - lR);
    float edgeV = abs(lU - lD);
    float edge = max(edgeH, edgeV);
    
    // Sharpening amount based on contrast and edge
    float sharp = Sharpness * Sharpness;  // Square for smoother falloff
    float amount = sharp * contrast * edge;
    amount = clamp(amount, 0.0, 1.0);
    
    // Unsharp mask
    vec3 blur = (l + r + u + d) * 0.25;
    vec3 sharpened = color + (color - blur) * amount;
    
    // Depth-aware: reduce sharpening on depth edges (prevents ringing on geometry edges)
    float dC = texture(DepthTexture, uv).r;
    float dL = texture(DepthTexture, uv + vec2(-texel.x, 0.0)).r;
    float dR = texture(DepthTexture, uv + vec2(texel.x, 0.0)).r;
    float dU = texture(DepthTexture, uv + vec2(0.0, -texel.y)).r;
    float dD = texture(DepthTexture, uv + vec2(0.0, texel.y)).r;
    
    float depthEdge = max(abs(dL - dR), abs(dU - dD));
    amount *= 1.0 - smoothstep(0.0, DepthEdgeThreshold * 2.0, depthEdge);
    
    return mix(color, sharpened, amount);
}

void main() {
    vec2 uv = texCoord;
    vec2 texel = 1.0 / InputSize;
    
    // Step 1: EASU upsampling with edge-aware interpolation
    vec3 color = easuSample(uv, texel);
    
    // Step 2: CAS sharpening (depth-aware)
    color = casSharpen(uv, texel, color);
    
    // Output
    fragColor = vec4(color, 1.0);
}