#version 300 es
// Deband filter implementing the algorithm documented by f3kdb/flash3kyuu_deband and neo_f3kdb
// (their own README/parameter docs, confirmed consistent across both projects' current and
// historical documentation): sample_mode=2 ("square", both projects' own default) with
// blur_first=true (also both projects' own default). This file is an independent implementation
// written from that published, natural-language description of the algorithm's behaviour — it
// was not copied or adapted from either project's source code (which is GPL-3.0-licensed; this
// SSIV project is Apache 2.0). Algorithms and documented behaviour are not copyrightable
// expression, only a particular source implementation of them is, so writing fresh GLSL that
// reproduces the documented behaviour does not carry the original project's license.
//
// The documented mechanism: the current pixel is compared against the AVERAGE of 4 reference
// pixels at the diagonal corners of a square whose half-size is a per-pixel random distance in
// [1, u_range]; if the difference is below u_threshold, the pixel is replaced by that average.
// Grain (small per-pixel random noise) is added afterward as the algorithm's own documented
// final stage, to avoid a flat, artificial-looking debanded region.
//
// Differences from the original (both are adaptations, not the original algorithm's own
// choices, so documented explicitly rather than left implicit):
//  - the original runs per YUV plane with separate Y/Cb/Cr thresholds; this runs directly on
//    decoded RGB tiles (no access to original YCbCr planes — see Item 2's research notes on why),
//    so one threshold applies identically to R, G and B.
//  - the original's random distance/grain uses a seeded per-frame PRNG (irrelevant for a single
//    still image); this uses a standard GLSL hash-noise function, seeded by pixel position only,
//    so the result is deterministic for a given tile (no flicker on redraw) rather than a
//    cryptographically real RNG, which the algorithm never required in the first place.
precision highp float;
precision highp int;

in highp vec2 v_texCoord;
out vec4 fragColor;

uniform highp sampler2D u_texture;
uniform highp vec2 u_texelSize;
uniform float u_range;       // banding detection range in pixels; f3kdb's own default is 15, but
                             // GpuFilteringDecoder only decodes a 4px overlap apron per tile (see
                             // that file's doc comment — sized for the largest SHARPEN kernel's
                             // radius), so GpuTileRenderer caps this uniform to 4 rather than
                             // widening every tile's apron just for deband: a wider apron would
                             // add real decode overhead to every tile, for an effect that is soft
                             // and rarely visible at a seam even when slightly under-radius,
                             // unlike a sharpen kernel's clamped-edge artefact. This does mean
                             // deband here reaches less far than f3kdb's own recommended range —
                             // still effective on the sharper local banding steps most common in
                             // compressed webtoon gradients, less so on very slow, wide bands.
uniform float u_threshold;   // 0..1 (already scaled from f3kdb's 0..255-ish range); default ~64/255
uniform float u_grain;       // 0..1 grain amplitude

// Standard GLSL hash noise (Dave Hoskins' style single-iteration hash), not part of the ported
// algorithm itself — just the per-pixel pseudo-random source the algorithm needs for the random
// reference distance and the grain, in place of f3kdb's seeded CPU PRNG.
float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// Optional pointwise vibrance tail — SweetFX/CeeJay.dk Vibrance, identical formula to
// manga_enhance.frag's (see that file for the full attribution/derivation notes). Fusing this
// into whichever shader is the pipeline's actual LAST stage (see GpuTileRenderer's dispatch
// logic) is a genuinely free optimisation: vibrance is purely pointwise (reads only the current
// pixel, no neighbours), so tacking it onto an existing draw call's output needs no data a
// separate pass wouldn't already have computed, and the uniform branch below is the same for
// every pixel in the draw call (not data-dependent), so it costs nothing when disabled and adds
// no warp divergence when enabled.
uniform bool u_enableVibrance;
uniform float u_vibranceIntensity;

vec3 applyVibranceTail(vec3 color) {
    if (!u_enableVibrance) return color;
    highp vec3 coefLuma = vec3(0.212656, 0.715158, 0.072186);
    float luma = dot(coefLuma, color);
    float max_color = max(color.r, max(color.g, color.b));
    float min_color = min(color.r, min(color.g, color.b));
    float color_saturation = max_color - min_color;
    float coeffVibrance = u_vibranceIntensity;
    return mix(vec3(luma), color,
               1.0 + (coeffVibrance * (1.0 - (sign(coeffVibrance) * color_saturation))));
}

void main() {
    vec3 c = texture(u_texture, v_texCoord).rgb;
    // Integer pixel coordinates: normalised v_texCoord differs by only ~1/width between
    // neighbouring pixels, which gives the hash almost no entropy (correlated, non-noisy
    // distance/grain). Whole-pixel coordinates decorrelate neighbours properly.
    vec2 px = floor(v_texCoord / u_texelSize);

    // Per-pixel random square half-size in [1, u_range] (rounded to whole texels, matching the
    // original's integer-pixel reference distance).
    float dist = floor(1.0 + hash(px) * max(u_range - 1.0, 0.0) + 0.5);
    vec2 d = dist * u_texelSize;

    // The 4 diagonal corners of the square (sample_mode=2's "square around current pixel").
    vec3 r0 = texture(u_texture, v_texCoord + vec2(-d.x, -d.y)).rgb;
    vec3 r1 = texture(u_texture, v_texCoord + vec2( d.x, -d.y)).rgb;
    vec3 r2 = texture(u_texture, v_texCoord + vec2(-d.x,  d.y)).rgb;
    vec3 r3 = texture(u_texture, v_texCoord + vec2( d.x,  d.y)).rgb;
    vec3 avg = (r0 + r1 + r2 + r3) * 0.25;

    // blur_first=true (f3kdb's own default): compare against the average, not each reference
    // individually.
    vec3 diff = abs(c - avg);
    vec3 result = mix(c, avg, step(diff, vec3(u_threshold)));

    // Grain: small symmetric per-pixel noise, added only after the conditional average (matches
    // "optional dithered grain added after, as its final stage").
    float g1 = hash(px + vec2(0.37, 17.1));
    float g2 = hash(px + vec2(91.7, 53.9));
    float g3 = hash(px + vec2(173.3, 7.7));
    vec3 grain = (vec3(g1, g2, g3) - 0.5) * u_grain;

    fragColor = vec4(applyVibranceTail(clamp(result + grain, 0.0, 1.0)), 1.0);
}
