#version 150

// AMD FidelityFX Super Resolution 1 - RCAS pass, OPTIMIZED + EXPOSURE-INVARIANT LUMA.

uniform sampler2D InputSampler;
uniform vec2 InputSize;
uniform vec2 OutputSize;
uniform vec4 Params;

in vec2 texCoord;
out vec4 fragColor;

// Exposure-invariant luma - works with HDR/tonemapped colors from shader packs
float lumaOf(vec3 c) {
    vec3 mapped = c / (c + vec3(0.15));
    return dot(mapped, vec3(0.299, 0.587, 0.114));
}

// Fused fetch with luma
vec4 fetchLuma(vec2 p) {
    vec3 c = texture(InputSampler, (p + 0.5) / InputSize).rgb;
    return vec4(c, lumaOf(c));
}

void main() {
    vec2 sp = floor(texCoord * OutputSize);

    // Fetch 5 taps with luma
    vec4 tapB = fetchLuma(sp + vec2( 0.0, -1.0));
    vec4 tapD = fetchLuma(sp + vec2(-1.0,  0.0));
    vec4 tapE = fetchLuma(sp);
    vec4 tapF = fetchLuma(sp + vec2( 1.0,  0.0));
    vec4 tapH = fetchLuma(sp + vec2( 0.0,  1.0));

    // NZ calculation - fused
    float nz = (tapB.a + tapD.a + tapF.a + tapH.a) * 0.25 - tapE.a;
    float ringMax = max(max(max(tapB.a, tapD.a), max(tapE.a, tapF.a)), tapH.a);
    float ringMin = min(min(min(tapB.a, tapD.a), min(tapE.a, tapF.a)), tapH.a);
    nz = clamp(abs(nz) / max(ringMax - ringMin, 3.0517578125e-5), 0.0, 1.0);
    nz = 1.0 - 0.5 * nz;

    // Min/Max of ring
    vec3 mn4 = min(min(tapB.rgb, tapD.rgb), min(tapF.rgb, tapH.rgb));
    vec3 mx4 = max(max(tapB.rgb, tapD.rgb), max(tapF.rgb, tapH.rgb));

    // Fused hitMin/hitMax with branchless safe divide
    vec3 mn4e = min(mn4, tapE.rgb);
    vec3 mx4e = max(mx4, tapE.rgb);
    vec3 fourMx4 = 4.0 * mx4e;
    vec3 fourMn4 = 4.0 * mn4e;

    // Safe divide: replace small denominators with 1.0
    vec3 safeMx = mix(fourMx4, vec3(1.0), lessThan(abs(fourMx4), vec3(3.0517578125e-5)));
    vec3 safeMn = mix(fourMn4 - vec3(4.0), vec3(1.0), lessThan(abs(fourMn4 - vec3(4.0)), vec3(3.0517578125e-5)));

    vec3 hitMin = mn4e / safeMx;
    vec3 hitMax = (vec3(1.0) - mx4e) / safeMn;

    // Lobe - max of RGB components
    vec3 lobeAbs = max(-hitMin, hitMax);
    float lobe = max(-0.2, min(max(lobeAbs.r, max(lobeAbs.g, lobeAbs.b)), 0.0));
    
    float sharpness = clamp(Params.x, 0.0, 1.0);
    sharpness *= sharpness;
    lobe *= sharpness * nz;

    float rcpL = 1.0 / (4.0 * lobe + 1.0);
    vec3 result = (lobe * (tapB.rgb + tapD.rgb + tapH.rgb + tapF.rgb) + tapE.rgb) * rcpL;

    fragColor = vec4(result, 1.0);
}