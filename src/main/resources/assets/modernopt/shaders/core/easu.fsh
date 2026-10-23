#version 150

// AMD FidelityFX Super Resolution 1 - EASU pass, ported from ffx_fsr1.h (MIT).
// The reference uses gather4 to fetch 2x2 quads; here every tap is fetched on its own,
// which yields bit-identical texel positions on any GL 3.2+ driver.

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

void easuSet(inout vec2 dir, inout float len, vec2 pp,
             bool biS, bool biT, bool biU, bool biV,
             float lA, float lB, float lC, float lD, float lE) {
    float w = 0.0;
    if (biS) {
        w = (1.0 - pp.x) * (1.0 - pp.y);
    } else if (biT) {
        w = pp.x * (1.0 - pp.y);
    } else if (biU) {
        w = (1.0 - pp.x) * pp.y;
    } else {
        w = pp.x * pp.y;
    }

    float dc = lD - lC;
    float cb = lC - lB;
    float lenX = max(abs(dc), abs(cb));
    lenX = 1.0 / max(lenX, 1.0 / 32768.0);
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
    lenX *= lenX;
    len += lenX * w;

    float ec = lE - lC;
    float ca = lC - lA;
    float lenY = max(abs(ec), abs(ca));
    lenY = 1.0 / max(lenY, 1.0 / 32768.0);
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
    lenY *= lenY;
    len += lenY * w;
}

void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len2,
             float lob, float clp, vec3 c) {
    vec2 v;
    v.x = off.x * dir.x + off.y * dir.y;
    v.y = off.x * (-dir.y) + off.y * dir.x;
    v *= len2;

    float d2 = v.x * v.x + v.y * v.y;
    d2 = min(d2, clp);

    float wB = (2.0 / 5.0) * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = (25.0 / 16.0) * wB - (25.0 / 16.0 - 1.0);

    float w = wB * wA;
    aC += c * w;
    aW += w;
}

void main() {
    vec2 ratio = InputSize / OutputSize;
    vec2 ip = texCoord * OutputSize;
    vec2 pp = ip * ratio + 0.5 * ratio - 0.5;
    vec2 fp = floor(pp);
    pp -= fp;

    vec3 tapB = fetch(fp + vec2( 0.0, -1.0));
    vec3 tapC = fetch(fp + vec2( 1.0, -1.0));
    vec3 tapE = fetch(fp + vec2(-1.0,  0.0));
    vec3 tapF = fetch(fp + vec2( 0.0,  0.0));
    vec3 tapG = fetch(fp + vec2( 1.0,  0.0));
    vec3 tapH = fetch(fp + vec2( 2.0,  0.0));
    vec3 tapI = fetch(fp + vec2(-1.0,  1.0));
    vec3 tapJ = fetch(fp + vec2( 0.0,  1.0));
    vec3 tapK = fetch(fp + vec2( 1.0,  1.0));
    vec3 tapL = fetch(fp + vec2( 2.0,  1.0));
    vec3 tapN = fetch(fp + vec2( 0.0,  2.0));
    vec3 tapO = fetch(fp + vec2( 1.0,  2.0));

    float bL = lumaOf(tapB);
    float cL = lumaOf(tapC);
    float eL = lumaOf(tapE);
    float fL = lumaOf(tapF);
    float gL = lumaOf(tapG);
    float hL = lumaOf(tapH);
    float iL = lumaOf(tapI);
    float jL = lumaOf(tapJ);
    float kL = lumaOf(tapK);
    float lL = lumaOf(tapL);
    float nL = lumaOf(tapN);
    float oL = lumaOf(tapO);

    vec2 dir = vec2(0.0);
    float len = 0.0;

    easuSet(dir, len, pp, true,  false, false, false, bL, eL, fL, gL, jL);
    easuSet(dir, len, pp, false, true,  false, false, cL, fL, gL, hL, kL);
    easuSet(dir, len, pp, false, false, true,  false, fL, iL, jL, kL, nL);
    easuSet(dir, len, pp, false, false, false, true,  gL, jL, kL, lL, oL);

    float dirR = dir.x * dir.x + dir.y * dir.y;
    bool zro = dirR < (1.0 / 32768.0);
    dirR = inversesqrt(max(dirR, 1.0 / 32768.0));
    if (zro) {
        dirR = 1.0;
        dir = vec2(1.0, 0.0);
    }
    dir *= dirR;

    len *= 0.5;
    len *= len;

    float stretch = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    float clp = 1.0 / lob;

    vec3 min4 = min(min(tapF, tapG), min(tapJ, tapK));
    vec3 max4 = max(max(tapF, tapG), max(tapJ, tapK));

    vec3 aC = vec3(0.0);
    float aW = 0.0;

    easuTap(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, tapB);
    easuTap(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, tapC);
    easuTap(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, tapI);
    easuTap(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, tapJ);
    easuTap(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, tapF);
    easuTap(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, tapE);
    easuTap(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, tapK);
    easuTap(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, tapL);
    easuTap(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, tapH);
    easuTap(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, tapG);
    easuTap(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, tapO);
    easuTap(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, tapN);

    vec3 result = aC / max(aW, 1.0 / 32768.0);
    result = min(max4, max(min4, result));

    fragColor = vec4(result, 1.0);
}