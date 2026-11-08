#version 150

// MSR 2.0 Spatial Pass - fused edge-aware upsampling + CAS sharpening.
//
// Input : temporal history at source resolution (R11G11B10F), scene depth at source
//         resolution (DEPTH_COMPONENT).
// Output: final image at native resolution.
//
// Two details matter for the black-screen bug:
//   * uvToInput() must return NORMALISED texture coordinates. The old version returned
//     pixel coordinates, so every texture() lookup clamped to the last texel.
//   * the final alpha is 1.0. A zero alpha with blending enabled makes the whole main
//     framebuffer transparent and therefore black.

uniform sampler2D TemporalColor;    // temporal history at source resolution
uniform sampler2D DepthTexture;     // scene depth at source resolution

uniform vec2 InputSize;             // source resolution
uniform float Sharpness;            // CAS sharpness 0..1
uniform float EdgeAwareStrength;    // edge-aware interpolation 0..1
uniform float DepthEdgeThreshold;   // depth gradient threshold for edges

in vec2 texCoord;
out vec4 fragColor;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// Output UV -> normalised source UV. Both images cover the same screen area, so the
// normalised coordinate is identical; the helper keeps the intent explicit.
vec2 uvToInput(vec2 uv) {
    return clamp(uv, vec2(0.0), vec2(1.0));
}

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

Neighborhood5 fetchNeighborhood5(vec2 uv) {
    vec2 inputUv = uvToInput(uv);
    vec2 inputTexel = 1.0 / InputSize;

    Neighborhood5 n;
    n.center = texture(TemporalColor, inputUv).rgb;
    n.left = texture(TemporalColor, inputUv + vec2(-inputTexel.x, 0.0)).rgb;
    n.right = texture(TemporalColor, inputUv + vec2(inputTexel.x, 0.0)).rgb;
    n.up = texture(TemporalColor, inputUv + vec2(0.0, -inputTexel.y)).rgb;
    n.down = texture(TemporalColor, inputUv + vec2(0.0, inputTexel.y)).rgb;

    n.lumaCenter = luma(n.center);
    n.lumaLeft = luma(n.left);
    n.lumaRight = luma(n.right);
    n.lumaUp = luma(n.up);
    n.lumaDown = luma(n.down);

    n.depthCenter = texture(DepthTexture, inputUv).r;
    n.depthLeft = texture(DepthTexture, inputUv + vec2(-inputTexel.x, 0.0)).r;
    n.depthRight = texture(DepthTexture, inputUv + vec2(inputTexel.x, 0.0)).r;
    n.depthUp = texture(DepthTexture, inputUv + vec2(0.0, -inputTexel.y)).r;
    n.depthDown = texture(DepthTexture, inputUv + vec2(0.0, inputTexel.y)).r;

    return n;
}

// 1.0 on a depth edge, ~0 on a flat surface.
float edgeAwareWeight(Neighborhood5 n) {
    float gradX = abs(n.depthRight - n.depthLeft);
    float gradY = abs(n.depthDown - n.depthUp);
    float maxGrad = max(gradX, gradY);
    float edge = smoothstep(DepthEdgeThreshold, DepthEdgeThreshold * 4.0, maxGrad);
    return edge * EdgeAwareStrength;
}

// Edge-direction biased interpolation: along a depth edge the sample follows the edge,
// on flat surfaces it falls back to a soft 4-neighbour average.
vec3 easuSample(Neighborhood5 n) {
    float horz = abs(n.lumaLeft - n.lumaRight);
    float vert = abs(n.lumaUp - n.lumaDown);

    float wH = 1.0 / max(horz, 0.001);
    float wV = 1.0 / max(vert, 0.001);
    float sumW = wH + wV;
    wH /= sumW;
    wV /= sumW;

    vec3 result = wH * (n.left + n.right) * 0.5 + wV * (n.up + n.down) * 0.5;
    float ew = edgeAwareWeight(n);
    result = mix(result, n.center, ew);
    return result;
}

// Contrast-adaptive sharpening of the upsampled result, reduced on depth edges.
vec3 casSharpen(Neighborhood5 n, vec3 easuColor) {
    if (Sharpness <= 0.0) {
        return easuColor;
    }

    float lMin = min(min(min(n.lumaLeft, n.lumaRight), min(n.lumaUp, n.lumaDown)), n.lumaCenter);
    float lMax = max(max(max(n.lumaLeft, n.lumaRight), max(n.lumaUp, n.lumaDown)), n.lumaCenter);
    float contrast = lMax - lMin;

    float edge = max(abs(n.lumaLeft - n.lumaRight), abs(n.lumaUp - n.lumaDown));

    float sharp = Sharpness * Sharpness;
    float amount = clamp(sharp * contrast * edge, 0.0, 1.0);

    vec3 blur = (n.left + n.right + n.up + n.down) * 0.25;
    vec3 sharpened = easuColor + (easuColor - blur) * amount;

    float depthEdge = max(abs(n.depthLeft - n.depthRight), abs(n.depthUp - n.depthDown));
    amount *= 1.0 - smoothstep(0.0, DepthEdgeThreshold * 2.0, depthEdge);

    return mix(easuColor, sharpened, amount);
}

void main() {
    Neighborhood5 n = fetchNeighborhood5(texCoord);

    vec3 color = easuSample(n);
    color = casSharpen(n, color);

    // Opaque output: zero alpha + enabled blending = black screen.
    fragColor = vec4(color, 1.0);
}
