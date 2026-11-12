#version 330
#extension GL_ARB_separate_shader_objects : require

// Physically based depth of field, single-pass gather.
// Each pixel walks a golden-angle spiral and takes neighbours whose own blur
// circle reaches it. Blur size comes from the thin-lens circle of confusion.

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;
uniform sampler2D GlowSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
    vec2 DepthSize;
    vec2 GlowSize;
};

layout(std140) uniform DofConfig {
    vec4 Lens;        // focus distance (m), aperture diameter (m), focal length (m), pixels per sensor meter
    vec4 DepthParams; // depth a, depth b (d = a + b/z), max blur radius (px), sample count
    vec4 Extra;       // depth value of the sky, debug view flag, aperture blades (0 = round)
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

// How far the aperture reaches in direction theta, as a fraction of the blur radius.
// A regular polygon inscribed in the blur circle, one corner pointing up (as an iris
// stopped down looks); 1 everywhere for a round aperture.
float apertureReach(float theta) {
    float n = Extra.z;
    if (n < 2.5) {
        return 1.0;
    }
    float seg = 6.2831853 / n;
    float a = mod(theta - 1.5707963, seg) - 0.5 * seg;
    return cos(0.5 * seg) / cos(a);
}

// Near-clipped, out-of-focus pixels count for far more (see highlight.fsh). A lamp is many
// times brighter than white paper, but an 8-bit frame stores both as about 1.0, so a plain
// average spreads a small light into a faint smudge; the extra weight restores some of that
// range and lets lights open into clear aperture shapes.
const float MAX_EXCESS = 80.0;

// How much a pixel acts as a bokeh source: none while it is in focus, fully once its blur
// circle is a few pixels wide. Must match highlight.fsh.
float bokehAmount(float r) {
    return smoothstep(3.0, 8.0, r);
}

// Extra weight a tap adds (rgb: weight * colour, a: weight), read from the spread map that
// highlight.fsh builds from out-of-focus pixels only, so a small light is caught by every tap
// inside its blur circle and a sharp bright surface adds none.
vec4 tapExcess(vec2 uv, float r) {
    vec4 g = texture(GlowSampler, uv);
    return g * g * MAX_EXCESS * bokehAmount(r);
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
    int count = int(DepthParams.w);

    // Samples spaced evenly in radius, not area, so even a 1-2 px blur gets plenty of taps
    // close in. Each tap is weighted by the ring area it stands for (2*pi*r*dr), which keeps
    // the disc average unbiased. Per-pixel rotation and radial offset turn the fixed pattern
    // into fine grain instead of rings.
    float noise = ign(gl_FragCoord.xy);
    float angle = noise * 6.2831853;
    float jitter = fract(noise * 7.31);
    float dr = maxR / float(count);
    float centreArea = 0.25 * dr * dr; // the centre tap stands for a disc of radius dr/2 (pi cancels)
    vec4 centreExcess = tapExcess(texCoord, centerR);
    vec3 sum = centreArea * (color + centreExcess.rgb);
    float total = centreArea * (1.0 + centreExcess.a);
    for (int i = 0; i < count; i++) {
        float radius = (float(i) + jitter) * dr;
        vec2 dir = vec2(cos(angle), sin(angle));
        vec2 uv = texCoord + dir * texel * radius;
        angle += GOLDEN_ANGLE;
        vec3 sampleColor = texture(InSampler, uv).rgb;
        float sampleZ = distanceAt(uv);
        float sampleR = blurRadius(sampleZ);
        // Background must not bleed over a sharper foreground.
        if (sampleZ > centerZ) {
            sampleR = clamp(sampleR, 0.0, centerR * 2.0);
        }
        // The tap's blur is the aperture shape centred on the tap; this pixel lies back along
        // -dir from it. Screen y runs up in texture space, matching the corner-up polygon.
        sampleR *= apertureReach(atan(-dir.y, -dir.x));
        float hit = smoothstep(radius - 0.5, radius + 0.5, sampleR);
        float area = 2.0 * radius * dr;
        // A tap whose blur doesn't reach this pixel stands in with the average so far.
        vec4 excess = tapExcess(uv, sampleR) * hit;
        sum += area * (mix(sum / total, sampleColor, hit) + excess.rgb);
        total += area * (1.0 + excess.a);
    }

    fragColor = vec4(sum / total, 1.0);
}
