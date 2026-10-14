#version 150
uniform sampler2D InSampler;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    ivec2 size = textureSize(InSampler, 0);
    ivec2 pixel = clamp(ivec2(floor(texCoord * vec2(size))), ivec2(0), size - ivec2(1));
    fragColor = texelFetch(InSampler, pixel, 0);
}
