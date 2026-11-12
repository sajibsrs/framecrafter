#version 330
#extension GL_ARB_separate_shader_objects : require

// Highlight spread for the bokeh, run as two passes (horizontal, then vertical) before the DOF
// gather. A small light is narrower than the space between gather taps, so on its own it
// sparkles: some pixels' taps land on it and most miss. Spreading each light's extra weight
// over a 13 px box first lets every tap inside its blur circle pick it up.
//
// Only out-of-focus pixels feed it, and never the sky. A bright surface in focus is not a
// bokeh source, and letting it in would smear its glow onto the blurred background around
// the subject; the sky is too broad to be one.
//
// Out: rgb = average of excess * colour, a = average of excess, each divided by MAX_EXCESS and
// square-root encoded so the 8-bit target keeps the faint end. The DOF pass decodes it.

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    vec2 DepthSize;
};

// Same block as dof.fsh, written each frame by DofEffect.
layout(std140) uniform DofConfig {
    vec4 Lens;
    vec4 DepthParams;
    vec4 Extra;
};

layout(std140) uniform HighlightConfig {
    vec4 Dir; // xy: step direction in texels, z: 1 = first pass (read scene colour), 0 = second
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const int HALF = 6;
const float MAX_EXCESS = 80.0;
const float FAR_AWAY = 100000.0;

// Extra weight of a pixel: none below 0.8, rising steeply to MAX_EXCESS at full brightness.
float highlightExcess(vec3 c) {
    float l = max(max(c.r, c.g), c.b);
    float h = max(l - 0.8, 0.0) * 5.0;
    return MAX_EXCESS * h * h * h;
}

// Must match dof.fsh.
float bokehAmount(float r) {
    return smoothstep(3.0, 8.0, r);
}

float distanceAt(vec2 uv) {
    float d = texture(DepthSampler, uv).r;
    if (abs(d - Extra.x) < 1e-7) {
        return FAR_AWAY;
    }
    float z = DepthParams.y / (d - DepthParams.x);
    return (z > 0.0 && z < FAR_AWAY) ? z : FAR_AWAY;
}

float blurRadius(float z) {
    float s = Lens.x;
    float coc = Lens.y * Lens.z * abs(z - s) / (z * max(s - Lens.z, 1e-4));
    return min(0.5 * coc * Lens.w, DepthParams.z);
}

void main() {
    vec2 step = Dir.xy / InSize;
    vec4 sum = vec4(0.0);
    for (int i = -HALF; i <= HALF; i++) {
        vec2 uv = texCoord + step * float(i);
        vec4 t = texture(InSampler, uv);
        if (Dir.z > 0.5) {
            float e = highlightExcess(t.rgb) / MAX_EXCESS;
            if (e > 0.0) {
                // The sky is a broad bright backdrop, not a light: boosting it would make sky
                // seen through leaves swell into white blobs. Lamps, torches and glints stay.
                float z = distanceAt(uv);
                e *= z < FAR_AWAY ? bokehAmount(blurRadius(z)) : 0.0;
            }
            sum += vec4(t.rgb * e, e);
        } else {
            sum += t * t;
        }
    }
    fragColor = sqrt(sum / float(2 * HALF + 1));
}
