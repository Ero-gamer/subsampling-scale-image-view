#version 300 es
precision mediump float;

// Texture coordinates and texel size need highp: at mediump (fp16 on many mobile GPUs) a
// coordinate near 1.0 has ~0.0005 resolution, which is about half a texel on a 1000 px wide
// tile — enough to corrupt the 3x3 neighbourhood offsets.
in highp vec2 v_texCoord;
out vec4 fragColor;

uniform highp sampler2D u_texture;
uniform highp vec2 u_texelSize;    // (1.0/width, 1.0/height)

// This program runs as two separate passes of the GPU pipeline (see GpuTileRenderer):
//  - the "pre" pass  : denoise and/or line darken (u_enableVibrance = false)
//  - the "post" pass : vibrance only (u_enableDenoise = u_enableDarken = false)
// Sharpening lives in its own programs (rcas.frag, adaptive_edge.frag, adaptive_sharpen.frag).
uniform bool  u_enableDenoise;      // Real bilateral filter (Tomasi & Manduchi 1998), 3x3
uniform float u_denoiseStrength;    // 0.0..1.0 — drives the range Gaussian's sigma_r
uniform bool  u_enableDarken;       // Line darken (Anime4K-inspired, not a port)
uniform bool  u_enableVibrance;     // Vibrance (SweetFX / CeeJay.dk)
uniform float u_vibranceIntensity;  // 0.0..1.0 — SweetFX "Vibrance" strength

float getLuma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec3 c5 = texture(u_texture, v_texCoord).rgb;
    vec3 color = c5;

    // The 8 neighbours are only needed by denoise / darken. The condition is a uniform, so the
    // branch is coherent across the whole draw call (not texture-dependent).
    if (u_enableDenoise || u_enableDarken) {
        highp vec2 o = u_texelSize;
        vec3 c1 = texture(u_texture, v_texCoord + vec2(-o.x, -o.y)).rgb;
        vec3 c2 = texture(u_texture, v_texCoord + vec2( 0.0, -o.y)).rgb;
        vec3 c3 = texture(u_texture, v_texCoord + vec2( o.x, -o.y)).rgb;
        vec3 c4 = texture(u_texture, v_texCoord + vec2(-o.x,  0.0)).rgb;
        vec3 c6 = texture(u_texture, v_texCoord + vec2( o.x,  0.0)).rgb;
        vec3 c7 = texture(u_texture, v_texCoord + vec2(-o.x,  o.y)).rgb;
        vec3 c8 = texture(u_texture, v_texCoord + vec2( 0.0,  o.y)).rgb;
        vec3 c9 = texture(u_texture, v_texCoord + vec2( o.x,  o.y)).rgb;

        // Real bilateral filter (Tomasi & Manduchi, "Bilateral Filtering for Gray and Color
        // Images", ICCV 1998): BF(x) = (1/k(x)) * sum_y f(y) * Gs(||x-y||) * Gr(|I(x)-I(y)|),
        // where Gs/Gr are Gaussians on spatial distance and range (luma) difference. Range
        // weights use luma (computed once, applied to all 3 channels) rather than per-channel
        // differences, standard practice to avoid hue-shifting artifacts. Replaces this
        // project's earlier range-only approximation (no spatial term) — see the project
        // history for the from-scratch numerical verification against a NumPy port of the
        // formula above (max diff ~0.002 across the full intensity range, i.e. within 8-bit
        // quantization noise) and for confirmation this genuinely preserves edges better (noise
        // std roughly halved within a flat region while a real step edge stays sharp).
        //
        // Spatial weights are fixed constants (sigma_s=0.8 texels, this shader's 3x3 footprint
        // never changes): Gs(dist=1, orthogonal neighbour) and Gs(dist=sqrt(2), diagonal).
        // Range sigma is the only strength-dependent parameter: 0.0 -> 0.03 (mild, only
        // near-identical neighbours blend), 1.0 -> 0.25 (aggressive, most of the 3x3 blends).
        if (u_enableDenoise) {
            const float SPATIAL_W_ORTHO = 0.4578333617716143; // exp(-1/(2*0.8^2))
            const float SPATIAL_W_DIAG  = 0.20961138715109787; // exp(-2/(2*0.8^2))

            float l5 = getLuma(color);
            float sigmaR = mix(0.03, 0.25, clamp(u_denoiseStrength, 0.0, 1.0));
            float twoSigmaR2 = 2.0 * sigmaR * sigmaR;

            vec3 accum = color; // centre: spatial=1, range=1 (zero distance both ways)
            float wSum = 1.0;
            float d; float w;

            d = getLuma(c1) - l5; w = SPATIAL_W_DIAG  * exp(-(d * d) / twoSigmaR2); accum += c1 * w; wSum += w;
            d = getLuma(c2) - l5; w = SPATIAL_W_ORTHO * exp(-(d * d) / twoSigmaR2); accum += c2 * w; wSum += w;
            d = getLuma(c3) - l5; w = SPATIAL_W_DIAG  * exp(-(d * d) / twoSigmaR2); accum += c3 * w; wSum += w;
            d = getLuma(c4) - l5; w = SPATIAL_W_ORTHO * exp(-(d * d) / twoSigmaR2); accum += c4 * w; wSum += w;
            d = getLuma(c6) - l5; w = SPATIAL_W_ORTHO * exp(-(d * d) / twoSigmaR2); accum += c6 * w; wSum += w;
            d = getLuma(c7) - l5; w = SPATIAL_W_DIAG  * exp(-(d * d) / twoSigmaR2); accum += c7 * w; wSum += w;
            d = getLuma(c8) - l5; w = SPATIAL_W_ORTHO * exp(-(d * d) / twoSigmaR2); accum += c8 * w; wSum += w;
            d = getLuma(c9) - l5; w = SPATIAL_W_DIAG  * exp(-(d * d) / twoSigmaR2); accum += c9 * w; wSum += w;

            color = accum / wSum;
        }

        // Line darken (Anime4K-inspired heuristic, not the Anime4K algorithm): pulls dark
        // pixels toward their darkest 8-neighbour below luma 0.6, capped at 35% blend.
        if (u_enableDarken) {
            float lumaC = getLuma(color);
            float minLuma = min(min(min(getLuma(c1), getLuma(c2)), getLuma(c3)),
                            min(min(getLuma(c4), getLuma(c6)),
                                min(min(getLuma(c7), getLuma(c8)), getLuma(c9))));
            if (lumaC < 0.6) {
                float darkenFactor = smoothstep(0.0, 0.6, lumaC);
                color = mix(color, color * (minLuma / (lumaC + 0.001)),
                            (1.0 - darkenFactor) * 0.35);
            }
        }
    }

    // Vibrance — SweetFX Vibrance.fx v1.1.1 by Christian Cann Schuldt Jensen ~ CeeJay.dk
    // (MIT licence, Copyright (c) 2014 CeeJayDK), RGB balance fixed at (1,1,1).
    // Pixels with little colour get a larger saturation boost than already-vivid ones.
    if (u_enableVibrance) {
        highp vec3 coefLuma = vec3(0.212656, 0.715158, 0.072186);
        float luma = dot(coefLuma, color);
        float max_color = max(color.r, max(color.g, color.b));
        float min_color = min(color.r, min(color.g, color.b));
        float color_saturation = max_color - min_color;
        float coeffVibrance = u_vibranceIntensity;
        color = mix(vec3(luma), color,
                    1.0 + (coeffVibrance * (1.0 - (sign(coeffVibrance) * color_saturation))));
    }

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
