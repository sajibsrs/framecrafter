#version 330
#extension GL_ARB_separate_shader_objects : require

// Camera body and lens character, after the depth of field:
// lens distortion, vignetting, exposure, white balance, picture style and ISO grain.

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform GradeConfig {
    vec4 Tone;    // exposure gain, grain amplitude, contrast, saturation
    vec4 Balance; // white balance gain (linear rgb), unused
    vec4 Lens;    // distortion k (+barrel / -pincushion), corner falloff (stops), frame seed, aspect w/h
    vec4 Misc;    // bypass (depth debug view)
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec3 toLinear(vec3 c) { return pow(max(c, 0.0), vec3(2.2)); }
vec3 toDisplay(vec3 c) { return pow(max(c, 0.0), vec3(1.0 / 2.2)); }

float hash(vec2 p, float seed) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031 + seed * 0.0137);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    if (Misc.x > 0.5) {
        fragColor = texture(InSampler, texCoord);
        return;
    }

    // Positions centred, aspect-corrected, with r2 = 1 at the frame corner.
    float aspect = Lens.w;
    vec2 p = (texCoord - 0.5) * vec2(aspect, 1.0);
    float cornerR2 = 0.25 * (aspect * aspect + 1.0);
    float r2 = dot(p, p) / cornerR2;

    // Radial distortion, scaled so the frame stays filled: barrel keeps the corners on the
    // corners, pincushion keeps the top and bottom edge centres on the edge.
    float k = Lens.x;
    float edgeR2 = 1.0 / (aspect * aspect + 1.0);
    float norm = k > 0.0 ? 1.0 + k : 1.0 + k * edgeR2;
    vec2 src = p * (1.0 + k * r2) / norm;
    vec2 uv = src / vec2(aspect, 1.0) + 0.5;

    vec3 col = toLinear(texture(InSampler, uv).rgb);

    // White balance: a fixed gain, so colour doesn't shift with framing.
    col *= Balance.rgb;

    // Exposure, then corner falloff in stops.
    col *= Tone.x * exp2(-Lens.y * r2);

    // Picture style in display space.
    vec3 disp = toDisplay(col);
    disp = (disp - 0.5) * Tone.z + 0.5;
    float luma = dot(disp, LUMA);
    disp = mix(vec3(luma), disp, Tone.w);

    // ISO grain: triangular noise, stronger in the shadows, changing every frame.
    if (Tone.y > 0.0) {
        float n = hash(gl_FragCoord.xy, Lens.z) + hash(gl_FragCoord.xy + 17.0, Lens.z + 3.0) - 1.0;
        disp += n * Tone.y * (1.25 - 0.75 * clamp(luma, 0.0, 1.0));
    }

    fragColor = vec4(clamp(disp, 0.0, 1.0), 1.0);
}
