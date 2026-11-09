#version 330
#extension GL_ARB_separate_shader_objects : require

// Physically based depth of field, single-pass gather.
// Each pixel walks a golden-angle spiral and takes neighbours whose own blur
// circle reaches it. Blur size comes from the thin-lens circle of confusion.

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    vec2 DepthSize;
};

layout(std140) uniform DofConfig {
    vec4 Lens;        // focus distance (m), aperture diameter (m), focal length (m), pixels per sensor meter
    vec4 DepthParams; // depth a, depth b (d = a + b/z), max blur radius (px), spiral step
    vec4 Extra;       // depth value of the sky, debug view flag
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const float GOLDEN_ANGLE = 2.39996323;
const float FAR_AWAY = 100000.0;

float distanceAt(vec2 uv) {
    float d = texture(DepthSampler, uv).r;
    if (abs(d - Extra.x) < 1e-7) {
        return FAR_AWAY;
    }
    float z = DepthParams.y / (d - DepthParams.x);
    return (z > 0.0 && z < FAR_AWAY) ? z : FAR_AWAY;
}

// Blur radius in pixels: c = A * f * |z - s| / (z * (s - f)).
float blurRadius(float z) {
    float s = Lens.x;
    float A = Lens.y;
    float f = Lens.z;
    float coc = A * f * abs(z - s) / (z * max(s - f, 1e-4));
    return min(0.5 * coc * Lens.w, DepthParams.z);
}

// Per-pixel spiral rotation turns ring banding into fine noise.
float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

void main() {
    if (Extra.y > 0.5) {
        float d = texture(DepthSampler, texCoord).r;
        float z = distanceAt(texCoord);
        fragColor = vec4(d, clamp(log2(z + 1.0) / 8.0, 0.0, 1.0), blurRadius(z) / DepthParams.z, 1.0);
        return;
    }
    vec2 texel = 1.0 / InSize;
    float centerZ = distanceAt(texCoord);
    float centerR = blurRadius(centerZ);
    vec3 color = texture(InSampler, texCoord).rgb;
    float maxR = DepthParams.z;
    float step = DepthParams.w;

    float total = 1.0;
    float radius = step;
    float angle = ign(gl_FragCoord.xy) * 6.2831853;
    for (int i = 0; i < 1024 && radius < maxR; i++) {
        vec2 uv = texCoord + vec2(cos(angle), sin(angle)) * texel * radius;
        vec3 sampleColor = texture(InSampler, uv).rgb;
        float sampleZ = distanceAt(uv);
        float sampleR = blurRadius(sampleZ);
        // Background must not bleed over a sharper foreground.
        if (sampleZ > centerZ) {
            sampleR = clamp(sampleR, 0.0, centerR * 2.0);
        }
        float weight = smoothstep(radius - 0.5, radius + 0.5, sampleR);
        color += mix(color / total, sampleColor, weight);
        total += 1.0;
        radius += step / radius;
        angle += GOLDEN_ANGLE;
    }

    fragColor = vec4(color / total, 1.0);
}
