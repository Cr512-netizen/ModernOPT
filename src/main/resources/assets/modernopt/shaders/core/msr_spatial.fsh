#version 150

// MSR 2.0 Spatial Pass - Fused EASU + RCAS + Edge-Aware Interpolation
// Single fragment shader pass for TBDR compatibility (iGPU, mobile)
// Optimized: Fused EASU + CAS to share 5-tap neighborhood samples
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

// Luma calculation (rec709)
float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// Shared 5-tap neighborhood structure
struct Neighborhood5 {
    vec3 center;
    vec3 left;
    vec3 right;
    vec3 up;
    vec3 down;
    float lumaCenter;
    float lumaLeft;
    float lumaRight;
    float lumaUp;
    float lumaDown;
    float depthCenter;
    float depthLeft;
    float depthRight;
    float depthUp;
    float depthDown;
};

Neighborhood5 fetchNeighborhood5(vec2 uv, vec2 texel) {
    Neighborhood5 n;
    n.center = texture(TemporalColor, uv).rgb;
    n.left = texture(TemporalColor, uv + vec2(-texel.x, 0.0)).rgb;
    n.right = texture(TemporalColor, uv + vec2(texel.x, 0.0)).rgb;
    n.up = texture(TemporalColor, uv + vec2(0.0, -texel.y)).rgb;
    n.down = texture(TemporalColor, uv + vec2(0.0, texel.y)).rgb;
    
    n.lumaCenter = luma(n.center);
    n.lumaLeft = luma(n.left);
    n.lumaRight = luma(n.right);
    n.lumaUp = luma(n.up);
    n.lumaDown = luma(n.down);
    
    n.depthCenter = texture(DepthTexture, uv).r;
    n.depthLeft = texture(DepthTexture, uv + vec2(-texel.x, 0.0)).r;
    n.depthRight = texture(DepthTexture, uv + vec2(texel.x, 0.0)).r;
    n.depthUp = texture(DepthTexture, uv + vec2(0.0, -texel.y)).r;
    n.depthDown = texture(DepthTexture, uv + vec2(0.0, texel.y)).r;
    
    return n;
}

float edgeAwareWeight(Neighborhood5 n) {
    float gradX = abs(n.depthRight - n.depthLeft);
    float gradY = abs(n.depthDown - n.depthUp);
    float maxGrad = max(gradX, gradY);
    float edge = smoothstep(DepthEdgeThreshold, DepthEdgeThreshold * 4.0, maxGrad);
    return edge * EdgeAwareStrength;
}

vec3 easuSample(Neighborhood5 n, vec2 texel) {
    // Edge directions
    float horz = abs(n.lumaLeft - n.lumaRight);
    float vert = abs(n.lumaUp - n.lumaDown);
    
    // Interpolation weights based on edge direction
    float wH = 1.0 / max(horz, 0.001);
    float wV = 1.0 / max(vert, 0.001);
    float sumW = wH + wV;
    wH /= sumW;
    wV /= sumW;
    
    // Edge-aware weight
    float ew = edgeAwareWeight(n);
    
    // Blend horizontal/vertical based on edge direction
    vec3 result = wH * (n.left + n.right) * 0.5 + wV * (n.up + n.down) * 0.5;
    
    // Apply edge-aware bias
    result = mix(result, n.center, ew);
    
    return result;
}

vec3 casSharpen(Neighborhood5 n) {
    if (Sharpness <= 0.0) return n.center;
    
    // Local contrast (reuse luma values from neighborhood)
    float lMin = min(min(min(n.lumaLeft, n.lumaRight), min(n.lumaUp, n.lumaDown)), n.lumaCenter);
    float lMax = max(max(max(n.lumaLeft, n.lumaRight), max(n.lumaUp, n.lumaDown)), n.lumaCenter);
    float contrast = lMax - lMin;
    
    // Edge detection
    float edgeH = abs(n.lumaLeft - n.lumaRight);
    float edgeV = abs(n.lumaUp - n.lumaDown);
    float edge = max(edgeH, edgeV);
    
    // Sharpening amount based on contrast and edge
    float sharp = Sharpness * Sharpness;
    float amount = sharp * contrast * edge;
    amount = clamp(amount, 0.0, 1.0);
    
    // Unsharp mask - reuse already-fetched neighbors
    vec3 blur = (n.left + n.right + n.up + n.down) * 0.25;
    vec3 sharpened = n.center + (n.center - blur) * amount;
    
    // Depth-aware: reduce sharpening on depth edges
    float depthEdge = max(abs(n.depthLeft - n.depthRight), abs(n.depthUp - n.depthDown));
    amount *= 1.0 - smoothstep(0.0, DepthEdgeThreshold * 2.0, depthEdge);
    
    return mix(n.center, sharpened, amount);
}

void main() {
    vec2 uv = texCoord;
    vec2 texel = 1.0 / InputSize;
    
    // Fetch shared 5-tap neighborhood once
    Neighborhood5 n = fetchNeighborhood5(uv, texel);
    
    // Step 1: EASU upsampling with edge-aware interpolation
    vec3 color = easuSample(n, texel);
    
    // Step 2: CAS sharpening (depth-aware) - reuses neighborhood data
    color = casSharpen(n);
    
    fragColor = vec4(color, 1.0);
}