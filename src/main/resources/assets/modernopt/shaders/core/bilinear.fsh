#version 150

uniform sampler2D InputSampler;
uniform vec2 InputSize;
uniform vec2 OutputSize;
uniform vec4 Params;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    fragColor = vec4(texture(InputSampler, texCoord).rgb, 1.0);
}