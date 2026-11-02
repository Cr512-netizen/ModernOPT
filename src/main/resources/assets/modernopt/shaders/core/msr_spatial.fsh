#version 150

// MSR 2.0 Spatial Pass - Fused EASU + RCAS + Edge-Aware Interpolation
// Single fragment shader pass for TBDR compatibility (iGPU, mobile)
// Input: Temporal pass output (source resolution), depth buffer (source resolution)
// Output: Final upscaled + sharpened image (output resolution)
//
// EASU upsamples from InputSize to OutputSize along depth edges so block faces stay sharp.
// CAS sharpens the EASU result (not the raw source) and is depth-aware: it is strongest on
// geometry edges and fades to nothing on flat depth surfaces (grass, leaves, water).

uniform sampler2D TemporalColor;    // Output from temporal pass (source resolution)
uniform sampler2D DepthTexture;     // Source-resolution depth buffer

uniform vec2 InputSize;             // Source resolution (temporal output size)
uniform vec2 OutputSize;            // Screen size (native / output resolution)
uniform float Sharpness;            // CAS sharpness 0.0-1.0
uniform float EdgeAwareStrength;    // Edge-aware interpolation strength 0.0-1.0
uniform float DepthEdgeThreshold;   // Depth gradient threshold for edges

in vec2 texCoord;
out vec4 fragColor;

// Luma calculation (rec709)
float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// Shared 5-tap neighborhood structure, sampled in source space.
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

// Depth-edge weight: 1.0 on a block face, ~0 on a flat surface.
float edgeAwareWeight(Neighborhood5 n) {
    float gradX = abs(n.depthRight - n.depthLeft);
    float gradY = abs(n.depthDown - n.depthUp);
    float maxGrad = max(gradX, gradY);
    float edge = smoothstep(DepthEdgeThreshold, DepthEdgeThreshold * 4.0, maxGrad);
    return edge * EdgeAwareStrength;
}

// EASU: upsample the source pixel to output resolution, biased toward the edge direction so
// block faces are interpolated along the edge instead of across it.
vec3 easuSample(Neighborhood5 n, vec2 texel) {
    float horz = abs(n.lumaLeft - n.lumaRight);
    float vert = abs(n.lumaUp - n.lumaDown);

    float wH = 1.0 / max(horz, 0.001);
    float wV = 1.0 / max(vert, 0.001);
    float sumW = wH + wV;
    wH /= sumW;
    wV /= sumW;

    float ew = edgeAwareWeight(n);

    vec3 result = wH * (n.left + n.right) * 0.5 + wV * (n.up + n.down) * 0.5;
    // On a depth edge keep the edge-aligned interpolation; off-edge blend toward center.
    result = mix(result, n.center, ew);

    return result;
}

// CAS: sharpen the EASU result. Adaptive to local contrast and edge strength so it does not
// ring on noisy textures (foliage, grass) while still punching up block outlines.
vec3 casSharpen(Neighborhood5 n, vec3 easuColor) {
    if (Sharpness <= 0.0) return easuColor;

    float lMin = min(min(min(n.lumaLeft, n.lumaRight), min(n.lumaUp, n.lumaDown)), n.lumaCenter);
    float lMax = max(max(max(n.lumaLeft, n.lumaRight), max(n.lumaUp, n.lumaDown)), n.lumaCenter);
    float contrast = lMax - lMin;

    float edgeH = abs(n.lumaLeft - n.lumaRight);
    float edgeV = abs(n.lumaUp - n.lumaDown);
    float edge = max(edgeH, edgeV);

    float sharp = Sharpness * Sharpness;
    float amount = sharp * contrast * edge;
    amount = clamp(amount, 0.0, 1.0);

    // Unsharp mask around the EASU result, not the raw source.
    vec3 blur = (n.left + n.right + n.up + n.down) * 0.25;
    vec3 sharpened = easuColor + (easuColor - blur) * amount;

    // Reduce sharpening on depth edges (already handled by EASU) and on flat surfaces.
    float depthEdge = max(abs(n.depthLeft - n.depthRight), abs(n.depthUp - n.depthDown));
    amount *= 1.0 - smoothstep(0.0, DepthEdgeThreshold * 2.0, depthEdge);

    return mix(easuColor, sharpened, amount);
}

void main() {
    vec2 uv = texCoord;
    vec2 texel = 1.0 / InputSize;

    // Fetch shared 5-tap neighborhood once, in source space.
    Neighborhood5 n = fetchNeighborhood5(uv, texel);

    // Step 1: EASU upsampling with edge-aware interpolation.
    vec3 color = easuSample(n, texel);

    // Step 2: CAS sharpening of the EASU result (depth-aware).
    color = casSharpen(n, color);

    fragColor = vec4(color, 1.0);
}