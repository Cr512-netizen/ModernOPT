#version 150
uniform sampler2D InSampler;
in vec2 texCoord;
out vec4 fragColor;

float luma(vec3 color) {
    return dot(color, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 inverseSize = 1.0 / vec2(textureSize(InSampler, 0));
    vec3 rgbNW = texture(InSampler, texCoord + vec2(-1.0, -1.0) * inverseSize).rgb;
    vec3 rgbNE = texture(InSampler, texCoord + vec2( 1.0, -1.0) * inverseSize).rgb;
    vec3 rgbSW = texture(InSampler, texCoord + vec2(-1.0,  1.0) * inverseSize).rgb;
    vec3 rgbSE = texture(InSampler, texCoord + vec2( 1.0,  1.0) * inverseSize).rgb;
    vec3 rgbM  = texture(InSampler, texCoord).rgb;

    float lumaNW = luma(rgbNW);
    float lumaNE = luma(rgbNE);
    float lumaSW = luma(rgbSW);
    float lumaSE = luma(rgbSE);
    float lumaM  = luma(rgbM);
    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));

    vec2 direction = vec2(
        -((lumaNW + lumaNE) - (lumaSW + lumaSE)),
          (lumaNW + lumaSW) - (lumaNE + lumaSE)
    );
    float reduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * 0.03125, 0.0078125);
    float reciprocalMin = 1.0 / (min(abs(direction.x), abs(direction.y)) + reduce);
    direction = clamp(direction * reciprocalMin, vec2(-8.0), vec2(8.0)) * inverseSize;

    vec3 rgbA = 0.5 * (
        texture(InSampler, texCoord + direction * (1.0 / 3.0 - 0.5)).rgb +
        texture(InSampler, texCoord + direction * (2.0 / 3.0 - 0.5)).rgb
    );
    vec3 rgbB = rgbA * 0.5 + 0.25 * (
        texture(InSampler, texCoord + direction * -0.5).rgb +
        texture(InSampler, texCoord + direction *  0.5).rgb
    );
    float lumaB = luma(rgbB);
    fragColor = vec4((lumaB < lumaMin || lumaB > lumaMax) ? rgbA : rgbB, 1.0);
}
