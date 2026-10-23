#version 150

// SoftScale: 9-tap tent centred on the destination pixel. The radius is measured in
// destination texels, so the very same filter behaves like a mild blur when upscaling
// and like an anti-ringing downsample when the image is reduced.

uniform sampler2D InputSampler;
uniform vec2 InputSize;
uniform vec2 OutputSize;
uniform vec4 Params;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    float strength = clamp(Params.x, 0.0, 1.0);
    float radius = mix(0.35, 1.75, strength);
    vec2 o = radius / OutputSize;

    vec3 sum = texture(InputSampler, texCoord).rgb * 0.25;

    sum += texture(InputSampler, texCoord + vec2( o.x, 0.0)).rgb * 0.125;
    sum += texture(InputSampler, texCoord + vec2(-o.x, 0.0)).rgb * 0.125;
    sum += texture(InputSampler, texCoord + vec2(0.0,  o.y)).rgb * 0.125;
    sum += texture(InputSampler, texCoord + vec2(0.0, -o.y)).rgb * 0.125;

    sum += texture(InputSampler, texCoord + vec2( o.x,  o.y)).rgb * 0.0625;
    sum += texture(InputSampler, texCoord + vec2(-o.x,  o.y)).rgb * 0.0625;
    sum += texture(InputSampler, texCoord + vec2( o.x, -o.y)).rgb * 0.0625;
    sum += texture(InputSampler, texCoord + vec2(-o.x, -o.y)).rgb * 0.0625;

    fragColor = vec4(sum, 1.0);
}