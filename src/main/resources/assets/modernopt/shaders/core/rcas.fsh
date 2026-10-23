#version 150

// AMD FidelityFX Super Resolution 1 - RCAS pass, ported from ffx_fsr1.h (MIT).
// Params.x is the user sharpness in 0..1; it is squared so low values fade out smoothly.

uniform sampler2D InputSampler;
uniform vec2 InputSize;
uniform vec2 OutputSize;
uniform vec4 Params;

in vec2 texCoord;
out vec4 fragColor;

vec3 fetch(vec2 p) {
    return texture(InputSampler, (p + 0.5) / InputSize).rgb;
}

float lumaOf(vec3 c) {
    return c.b * 0.5 + (c.r * 0.5 + c.g);
}

vec3 safeDiv(vec3 numerator, vec3 denominator) {
    vec3 safe = mix(denominator, vec3(1.0), lessThan(abs(denominator), vec3(1.0 / 4096.0)));
    return numerator / safe;
}

void main() {
    vec2 sp = floor(texCoord * OutputSize);

    vec3 tapB = fetch(sp + vec2( 0.0, -1.0));
    vec3 tapD = fetch(sp + vec2(-1.0,  0.0));
    vec3 tapE = fetch(sp);
    vec3 tapF = fetch(sp + vec2( 1.0,  0.0));
    vec3 tapH = fetch(sp + vec2( 0.0,  1.0));

    float bL = lumaOf(tapB);
    float dL = lumaOf(tapD);
    float eL = lumaOf(tapE);
    float fL = lumaOf(tapF);
    float hL = lumaOf(tapH);

    float nz = 0.25 * bL + 0.25 * dL + 0.25 * fL + 0.25 * hL - eL;
    float ringMax = max(max(max(bL, dL), max(eL, fL)), hL);
    float ringMin = min(min(min(bL, dL), min(eL, fL)), hL);
    nz = clamp(abs(nz) / max(ringMax - ringMin, 1.0 / 32768.0), 0.0, 1.0);
    nz = -0.5 * nz + 1.0;

    vec3 mn4 = min(min(tapB, tapD), min(tapF, tapH));
    vec3 mx4 = max(max(tapB, tapD), max(tapF, tapH));

    vec3 hitMin = min(mn4, tapE) / max(4.0 * mx4, vec3(1.0 / 32768.0));
    vec3 hitMax = safeDiv(vec3(1.0) - max(mx4, tapE), 4.0 * mn4 - 4.0);

    float lobeR = max(-hitMin.r, hitMax.r);
    float lobeG = max(-hitMin.g, hitMax.g);
    float lobeB = max(-hitMin.b, hitMax.b);

    float sharpness = clamp(Params.x, 0.0, 1.0);
    sharpness *= sharpness;

    float lobe = max(-0.2, min(max(lobeR, max(lobeG, lobeB)), 0.0)) * sharpness;
    lobe *= nz;

    float rcpL = 1.0 / (4.0 * lobe + 1.0);
    vec3 result = (lobe * (tapB + tapD + tapH + tapF) + tapE) * rcpL;

    fragColor = vec4(result, 1.0);
}