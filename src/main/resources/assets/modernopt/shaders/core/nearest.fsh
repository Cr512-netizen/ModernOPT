#version 150

uniform sampler2D InputSampler;
uniform vec2 InputSize;
uniform vec2 OutputSize;
uniform vec4 Params;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 texel = 1.0 / OutputSize;
    vec2 uv = (floor(texCoord * OutputSize) + 0.5) * texel;
    fragColor = vec4(texture(InputSampler, uv).rgb, 1.0);
}