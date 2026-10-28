#version 150

// AMD FidelityFX Super Resolution 1 - EASU pass, OPTIMIZED.
// Reduced redundant calculations, faster luma, fused operations.

uniform sampler2D InputSampler;
uniform vec2 InputSize;
uniform vec2 OutputSize;
uniform vec4 Params;

in vec2 texCoord;
out vec4 fragColor;

// Fused fetch with luma - single texture read per tap
vec4 fetchLuma(vec2 p) {
    vec3 c = texture(InputSampler, (p + 0.5) / InputSize).rgb;
    return vec4(c, c.r * 0.5 + c.g * 0.5 + c.b * 0.5);
}

// Optimized easuSet - branchless weight calculation
void easuSet(inout vec2 dir, inout float len, vec2 pp,
             float w, float lA, float lB, float lC, float lD, float lE) {
    float dc = lD - lC;
    float cb = lC - lB;
    float lenX = max(abs(dc), abs(cb));
    lenX = 1.0 / max(lenX, 3.0517578125e-5); // 1/32768
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
    len += lenX * lenX * w;

    float ec = lE - lC;
    float ca = lC - lA;
    float lenY = max(abs(ec), abs(ca));
    lenY = 1.0 / max(lenY, 3.0517578125e-5);
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
    len += lenY * lenY * w;
}

// Optimized easuTap - fused math
void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len2,
             float lob, float clp, vec3 c) {
    float vx = off.x * dir.x + off.y * dir.y;
    float vy = off.x * -dir.y + off.y * dir.x;
    vx *= len2.x;
    vy *= len2.y;

    float d2 = vx * vx + vy * vy;
    d2 = min(d2, clp);

    float wB = 0.4 * d2 - 1.0;      // 2/5 = 0.4
    float wA = lob * d2 - 1.0;
    wB = 1.5625 * wB * wB - 0.5625; // (25/16)*wB^2 - (25/16-1)
    wA *= wA;

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

    // Fetch all 12 taps with luma in one go
    vec4 tapB = fetchLuma(fp + vec2( 0.0, -1.0));
    vec4 tapC = fetchLuma(fp + vec2( 1.0, -1.0));
    vec4 tapE = fetchLuma(fp + vec2(-1.0,  0.0));
    vec4 tapF = fetchLuma(fp + vec2( 0.0,  0.0));
    vec4 tapG = fetchLuma(fp + vec2( 1.0,  0.0));
    vec4 tapH = fetchLuma(fp + vec2( 2.0,  0.0));
    vec4 tapI = fetchLuma(fp + vec2(-1.0,  1.0));
    vec4 tapJ = fetchLuma(fp + vec2( 0.0,  1.0));
    vec4 tapK = fetchLuma(fp + vec2( 1.0,  1.0));
    vec4 tapL = fetchLuma(fp + vec2( 2.0,  1.0));
    vec4 tapN = fetchLuma(fp + vec2( 0.0,  2.0));
    vec4 tapO = fetchLuma(fp + vec2( 1.0,  2.0));

    vec2 dir = vec2(0.0);
    float len = 0.0;

    // Bilinear weights
    float w00 = (1.0 - pp.x) * (1.0 - pp.y);
    float w10 = pp.x * (1.0 - pp.y);
    float w01 = (1.0 - pp.x) * pp.y;
    float w11 = pp.x * pp.y;

    easuSet(dir, len, pp, w00, bL, eL, fL, gL, jL);
    easuSet(dir, len, pp, w10, cL, fL, gL, hL, kL);
    easuSet(dir, len, pp, w01, fL, iL, jL, kL, nL);
    easuSet(dir, len, pp, w11, gL, jL, kL, lL, oL);

    float dirR = dir.x * dir.x + dir.y * dir.y;
    float invDirR = inversesqrt(max(dirR, 3.0517578125e-5));
    bool zro = dirR < 3.0517578125e-5;
    dir = zro ? vec2(1.0, 0.0) : dir * invDirR;

    len = len * 0.5 * len * 0.5; // len *= 0.5; len *= len;

    float maxAbsDir = max(abs(dir.x), abs(dir.y));
    float stretch = (dir.x * dir.x + dir.y * dir.y) / max(maxAbsDir, 3.0517578125e-5);
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    float lob = 0.5 + (0.21 - 0.5) * len; // 1/4 - 0.04 = 0.21
    float clp = 1.0 / lob;

    vec3 min4 = min(min(tapF.rgb, tapG.rgb), min(tapJ.rgb, tapK.rgb));
    vec3 max4 = max(max(tapF.rgb, tapG.rgb), max(tapJ.rgb, tapK.rgb));

    vec3 aC = vec3(0.0);
    float aW = 0.0;

    easuTap(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, tapB.rgb);
    easuTap(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, tapC.rgb);
    easuTap(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, tapI.rgb);
    easuTap(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, tapJ.rgb);
    easuTap(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, tapF.rgb);
    easuTap(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, tapE.rgb);
    easuTap(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, tapK.rgb);
    easuTap(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, tapL.rgb);
    easuTap(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, tapH.rgb);
    easuTap(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, tapG.rgb);
    easuTap(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, tapO.rgb);
    easuTap(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, tapN.rgb);

    vec3 result = aC / max(aW, 3.0517578125e-5);
    result = min(max4, max(min4, result));

    fragColor = vec4(result, 1.0);
}