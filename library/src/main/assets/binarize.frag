#version 300 es
// Local-threshold black & white ("binarize") for scanned manga pages: removes paper tint, scanner
// shadows and faint show-through so line art and tone become crisp black on white.
//
// Per pixel: luma is compared with the mean luma of itself and 8 sparse neighbours (4 axial + 4 diagonal at
// u_radius texels; GpuFilteringDecoder decodes a 4 px apron, so the radius stays within it and tiles join
// without seams). A page-wide threshold fails on uneven lighting; a local mean follows it.
// In flat areas (local contrast below LOW_CONTRAST) the local mean is meaningless (it is just the paper or
// ink level), so a fixed 0.5 threshold is used there instead: white paper stays white, solid black stays black
// and no "ghost" edges appear from noise.
// u_strength mixes the result with the original colour (1.0 = pure black and white).
// Independent implementation of a generic adaptive-threshold technique (Bradley/Sauvola family, no source copied).
precision highp float;
precision highp int;

in highp vec2 v_texCoord;
out vec4 fragColor;

uniform highp sampler2D u_texture;
uniform highp vec2 u_texelSize;
uniform float u_strength;
uniform float u_radius;

const float LOW_CONTRAST = 0.15;
const float FIXED_THRESHOLD = 0.5;
// Half-width of the soft step, in luma: keeps hard edges anti-aliased instead of shimmering with scaling.
const float SOFT = 0.02;

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec3 c = texture(u_texture, v_texCoord).rgb;
    float l = luma(c);
    vec2 d = u_radius * u_texelSize;

    float l1 = luma(texture(u_texture, v_texCoord + vec2(-d.x, 0.0)).rgb);
    float l2 = luma(texture(u_texture, v_texCoord + vec2(d.x, 0.0)).rgb);
    float l3 = luma(texture(u_texture, v_texCoord + vec2(0.0, -d.y)).rgb);
    float l4 = luma(texture(u_texture, v_texCoord + vec2(0.0, d.y)).rgb);
    float l5 = luma(texture(u_texture, v_texCoord + vec2(-d.x, -d.y)).rgb);
    float l6 = luma(texture(u_texture, v_texCoord + vec2(d.x, -d.y)).rgb);
    float l7 = luma(texture(u_texture, v_texCoord + vec2(-d.x, d.y)).rgb);
    float l8 = luma(texture(u_texture, v_texCoord + vec2(d.x, d.y)).rgb);

    float mn = min(l, min(min(min(l1, l2), min(l3, l4)), min(min(l5, l6), min(l7, l8))));
    float mx = max(l, max(max(max(l1, l2), max(l3, l4)), max(max(l5, l6), max(l7, l8))));
    float mean = (l + l1 + l2 + l3 + l4 + l5 + l6 + l7 + l8) / 9.0;

    // Smooth hand-over (no jump where local contrast crosses LOW_CONTRAST).
    float threshold = mix(FIXED_THRESHOLD, mean, smoothstep(LOW_CONTRAST, LOW_CONTRAST + 0.1, mx - mn));
    float bw = smoothstep(threshold - SOFT, threshold + SOFT, l);
    fragColor = vec4(mix(c, vec3(bw), clamp(u_strength, 0.0, 1.0)), 1.0);
}
