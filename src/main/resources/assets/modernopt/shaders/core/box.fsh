#version 150

// 2x2 box downsample used by SSAA: four bilinear taps half a destination texel apart
// cover the full 2x2 source block exactly.

uniform sampler2D InputSampler;
uniform vec2 InputSize;
uniform vec2 OutputSize;
uniform vec4 Params;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 o = 0.5 / OutputSize;

    vec3 sum = texture(InputSampler, texCoord + vec2(-o.x, -o.y)).rgb;
    sum += texture(InputSampler, texCoord + vec2( o.x, -o.y)).rgb;
    sum += texture(InputSampler, texCoord + vec2(-o.x,  o.y)).rgb;
    sum += texture(InputSampler, texCoord + vec2( o.x,  o.y)).rgb;

    fragColor = vec4(sum * 0.25, 1.0);
}