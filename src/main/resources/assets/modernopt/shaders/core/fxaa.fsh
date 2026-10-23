#version 150

// FXAA (luma based edge detection with the canonical two-tap resolve).
// Runs after the reconstruction filter, on a native resolution image.

uniform sampler2D InputSampler;
uniform vec2 InputSize;
uniform vec2 OutputSize;
uniform vec4 Params;

in vec2 texCoord;
out vec4 fragColor;

float lumaOf(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 texel = 1.0 / OutputSize;
    vec2 uv = texCoord;

    vec3 rgbM = texture(InputSampler, uv).rgb;
    float lM = lumaOf(rgbM);

    float lN = lumaOf(texture(InputSampler, uv + vec2(0.0, -texel.y)).rgb);
    float lS = lumaOf(texture(InputSampler, uv + vec2(0.0,  texel.y)).rgb);
    float lW = lumaOf(texture(InputSampler, uv + vec2(-texel.x, 0.0)).rgb);
    float lE = lumaOf(texture(InputSampler, uv + vec2( texel.x, 0.0)).rgb);

    float lMin = min(lM, min(min(lN, lS), min(lW, lE)));
    float lMax = max(lM, max(max(lN, lS), max(lW, lE)));

    if (lMax - lMin < max(0.0312, lMax * 0.125)) {
        fragColor = vec4(rgbM, 1.0);
        return;
    }

    float lNW = lumaOf(texture(InputSampler, uv + vec2(-texel.x, -texel.y)).rgb);
    float lNE = lumaOf(texture(InputSampler, uv + vec2( texel.x, -texel.y)).rgb);
    float lSW = lumaOf(texture(InputSampler, uv + vec2(-texel.x,  texel.y)).rgb);
    float lSE = lumaOf(texture(InputSampler, uv + vec2( texel.x,  texel.y)).rgb);

    float edgeH = abs(lNW + lNE - 2.0 * lN) + 2.0 * abs(lW + lE - 2.0 * lM) + abs(lSW + lSE - 2.0 * lS);
    float edgeV = abs(lNW + lSW - 2.0 * lW) + 2.0 * abs(lN + lS - 2.0 * lM) + abs(lNE + lSE - 2.0 * lE);

    bool horizontal = edgeH >= edgeV;

    float dirX = -((lNW + lNE) - (lSW + lSE));
    float dirY = (lNW + lSW) - (lNE + lSE);

    float dirReduce = max((lNW + lNE + lSW + lSE) * 0.25 * 0.03125, 1.0 / 128.0);
    float rcpDirMin = 1.0 / (min(abs(dirX), abs(dirY)) + dirReduce);

    vec2 dir = vec2(clamp(dirX * rcpDirMin, -8.0, 8.0), clamp(dirY * rcpDirMin, -8.0, 8.0)) * texel;

    if (horizontal) {
        dir.x = 0.0;
    } else {
        dir.y = 0.0;
    }

    vec2 offA = dir * (1.0 / 3.0 - 0.5);
    vec2 offB = dir * (2.0 / 3.0 - 0.5);

    vec3 rgbA = 0.5 * (texture(InputSampler, uv + offA).rgb + texture(InputSampler, uv + offB).rgb);
    vec3 rgbB = rgbA * 0.5 + 0.25 * (texture(InputSampler, uv + dir * -0.5).rgb
                                   + texture(InputSampler, uv + dir * 0.5).rgb);

    float lB = lumaOf(rgbB);
    vec3 result = (lB < lMin || lB > lMax) ? rgbA : rgbB;

    fragColor = vec4(result, 1.0);
}