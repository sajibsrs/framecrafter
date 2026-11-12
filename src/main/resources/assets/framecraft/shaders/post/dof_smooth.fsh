#version 330
#extension GL_ARB_separate_shader_objects : require

// Post-filter after the DOF gather. The gather rotates its tap spiral per pixel, which turns
// ring artifacts into fine grain; with live-view tap counts that grain shows as hatching in
// the blur and speckle in bokeh. This pass averages each blurred pixel with a small disc of
// neighbours, the disc growing with the pixel's own blur (up to MAX_PX), so a wide blur gets
// smoothed and anything in focus is left alone. Only neighbours that are themselves blurred
// that much take part, so a sharp subject's edge is never softened into the background.

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

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const float FAR_AWAY = 100000.0;
const float MAX_PX = 4.0;
const int TAPS = 16;
const float GOLDEN_ANGLE = 2.39996323;

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
    vec3 centre = texture(InSampler, texCoord).rgb;
    if (Extra.y > 0.5) {
        fragColor = vec4(centre, 1.0); // depth debug view passes through untouched
        return;
    }
    float r = blurRadius(distanceAt(texCoord));
    // A quarter of the blur radius stays well inside the blur, so it removes grain without
    // visibly widening the blur or rounding the bokeh's corners.
    float rr = min(0.25 * r, MAX_PX);
    if (rr < 0.75) {
        fragColor = vec4(centre, 1.0);
        return;
    }
    vec2 texel = 1.0 / InSize;
    vec3 sum = centre;
    float total = 1.0;
    float angle = 0.0;
    for (int i = 0; i < TAPS; i++) {
        // Vogel disc: even coverage of the disc with few taps.
        float d = rr * sqrt((float(i) + 0.5) / float(TAPS));
        vec2 uv = texCoord + vec2(cos(angle), sin(angle)) * texel * d;
        angle += GOLDEN_ANGLE;
        float w = smoothstep(0.5 * rr, rr, 0.25 * blurRadius(distanceAt(uv)));
        sum += texture(InSampler, uv).rgb * w;
        total += w;
    }
    fragColor = vec4(sum / total, 1.0);
}
