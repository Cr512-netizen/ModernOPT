#version 150

// FSR 2 style temporal accumulation for ModernOPT.
//
// This is not AMD's FSR 2 shader code: it is a compact accumulation pass built around the same
// ideas (jittered input, reprojection, history clamping, disocclusion rejection and a
// reactive mask). Motion vectors are reconstructed from the camera matrices, because the
// Minecraft/Sodium world pass does not expose per-object velocity buffers. Static geometry and
// the sky reproject exactly; moving objects fall back to neighbourhood clamping and the reactive
// test instead of their own velocities.
//
// The world is rendered with a sub-pixel projection jitter, but the image that is presented and
// stored in the history is NOT jittered. Sampling the colour and depth at `uv + Jitter * 0.5`
// undoes the jitter for the output pixel, and reprojection then uses plain unjittered NDC.
// Mixing jittered current frames with unjittered output was what made the whole image shake.

uniform sampler2D ColorSampler;   // EASU output of the current jittered frame (output size)
uniform sampler2D HistorySampler; // previous accumulated (unjittered) frame, alpha = linear view depth
uniform sampler2D DepthSampler;   // current jittered depth buffer (internal size)

uniform vec2 InputSize;
uniform vec2 OutputSize;
uniform vec2 Jitter;     // NDC offset that was added to the current projection matrix
uniform vec2 PrevJitter; // same value used by the previous frame (kept for diagnostics)
uniform vec3 CameraDelta; // current camera position - previous camera position, world axes
uniform mat4 ViewRotation;
uniform mat4 PrevViewRotation;
uniform mat4 InvProjection;  // inverse of the unjittered current projection
uniform mat4 PrevProjection; // unjittered previous projection
uniform vec4 Params;         // x = base blend, y = reactive strength, z = depth tolerance
uniform float HistoryValid;
uniform float DepthValid;    // 0 when no correct scene depth is available (Iris depth missing)

in vec2 texCoord;
out vec4 fragColor;

vec3 toYCoCg(vec3 c) {
    return vec3(0.25 * c.r + 0.5 * c.g + 0.25 * c.b,
                0.5 * c.r - 0.5 * c.b,
                -0.25 * c.r + 0.5 * c.g - 0.25 * c.b);
}

vec3 fromYCoCg(vec3 c) {
    float t = c.x - c.z;
    return vec3(t + c.y, c.x + c.z, t - c.y);
}

void main() {
    vec2 uv = texCoord;
    // The jittered frame stores the unjittered pixel `uv` half a jitter away. Undo it.
    vec2 sampleUv = clamp(uv + Jitter * 0.5, vec2(0.0), vec2(1.0));
    vec3 current = texture(ColorSampler, sampleUv).rgb;
    float rawDepth = texture(DepthSampler, sampleUv).r;
    bool depthValid = DepthValid > 0.5;
    // Without a usable depth buffer the best stable reprojection is rotation-only (as if the
    // whole screen were sky). Geometry parallax is then approximated by the neighbourhood clamp.
    float depthForProjection = depthValid ? rawDepth : 1.0;
    float currentDepth = 0.0;

    vec2 ndc = sampleUv * 2.0 - 1.0;
    vec4 clip = vec4(ndc - Jitter, depthForProjection * 2.0 - 1.0, 1.0);
    vec4 viewPosition = InvProjection * clip;
    if (abs(viewPosition.w) > 1.0e-6) {
        viewPosition /= viewPosition.w;
        currentDepth = depthValid ? max(-viewPosition.z, 1.0e-4) : 0.0;

        // The projection matrix contains the camera transform; the view rotation maps the
        // camera relative world position into view space. Undo it for reprojection.
        vec3 worldRelative = (vec4(viewPosition.xyz, 0.0) * ViewRotation).xyz;

        // The sky is infinitely far away: only camera rotation may move it.
        bool isSky = !depthValid || rawDepth > 0.9999995;
        vec3 previousRelative = worldRelative + (isSky ? vec3(0.0) : CameraDelta);
        vec3 previousView = (PrevViewRotation * vec4(previousRelative, 1.0)).xyz;
        vec4 previousClip = PrevProjection * vec4(previousView, 1.0);

        if (previousClip.w > 1.0e-6) {
            vec2 previousNdc = previousClip.xy / previousClip.w;
            // History frames are stored unjittered, so no PrevJitter correction here.
            vec2 previousUv = previousNdc * 0.5 + 0.5;

            bool valid = HistoryValid > 0.5;
            valid = valid && previousUv.x >= 0.0 && previousUv.x <= 1.0;
            valid = valid && previousUv.y >= 0.0 && previousUv.y <= 1.0;

            vec4 historySample = texture(HistorySampler, clamp(previousUv, vec2(0.0), vec2(1.0)));
            if (valid && depthValid) {
                float tolerance = max(Params.z * currentDepth, 0.15);
                valid = abs(historySample.a - currentDepth) <= tolerance;
            }

            // Clamp the history to the current 3x3 YCoCg neighbourhood. This is what removes
            // ghosts on moving entities and on geometry that the camera just revealed.
            vec2 texel = 1.0 / max(OutputSize, vec2(1.0));
            vec3 minimum = toYCoCg(current);
            vec3 maximum = minimum;
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    vec2 neighbourhoodUv = clamp(sampleUv + vec2(float(x), float(y)) * texel,
                            vec2(0.0), vec2(1.0));
                    vec3 sampleColor = texture(ColorSampler, neighbourhoodUv).rgb;
                    vec3 ycocg = toYCoCg(sampleColor);
                    minimum = min(minimum, ycocg);
                    maximum = max(maximum, ycocg);
                }
            }
            vec3 history = fromYCoCg(clamp(toYCoCg(historySample.rgb), minimum, maximum));

            if (valid) {
                float lumaDifference = abs(dot(current - history, vec3(0.299, 0.587, 0.114)));
                float reactive = smoothstep(0.05, 0.35, lumaDifference);
                vec2 velocityPixels = (previousUv - uv) * OutputSize;
                float speed = length(velocityPixels);
                float alpha = clamp(Params.x + Params.y * reactive + speed * 0.05, 0.0, 0.85);
                fragColor = vec4(mix(history, current, alpha), currentDepth);
                return;
            }
        }
    }

    fragColor = vec4(current, currentDepth);
}
