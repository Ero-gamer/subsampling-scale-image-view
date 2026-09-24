#version 300 es
precision mediump float;

// Texture coordinates and texel size need highp: at mediump (fp16 on many mobile GPUs) a
// coordinate near 1.0 has ~0.0005 resolution, which is about half a texel on a 1000 px wide
// tile — enough to corrupt the 3x3 neighbourhood offsets.
in highp vec2 v_texCoord;
out vec4 fragColor;

uniform sampler2D u_texture;
uniform highp vec2 u_texelSize;    // (1.0/width, 1.0/height)

// Every filter follows the same pattern: an enable flag plus an intensity, independent of
// all the others. Filters stack; none of them is mutually exclusive with another.
uniform bool  u_enableDenoise;               // 3x3 luma-weighted denoise
uniform float u_denoiseStrength;             // 0.0..1.0 — drives the luma falloff constant
uniform bool  u_enableDarken;                // Line darken (Anime4K-inspired, not a port)
uniform bool  u_enableVibrance;              // Vibrance / S-curve (RGB-space approximation)
uniform float u_vibranceIntensity;           // 0.0..1.0 — magnitude of the vibrance boost
uniform bool  u_enableRcasUsm;               // Sharpen: RCAS-style clamp + unsharp mask
uniform float u_rcasUsmIntensity;            // 0.0..1.0
uniform bool  u_enableAdaptiveSmoothstep;    // Sharpen: adaptive, smoothstep edge weight
uniform float u_adaptiveSmoothstepIntensity; // 0.0..1.0
uniform bool  u_enableAdaptiveSigmoid;       // Sharpen: adaptive, true logistic-sigmoid edge weight
uniform float u_adaptiveSigmoidIntensity;    // 0.0..1.0

float getLuma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// Logistic sigmoid 1/(1+e^-x), scaled and biased so the edge weight is exactly 0 at zero
// edge strength and exactly 1 at edge strength 0.5 (the practical maximum of |luma - blur|),
// with the steep part of the curve centred on edge strength 0.12.
float sigmoidEdgeWeight(float edge) {
    const float k   = 28.0;    // steepness
    const float mid = 0.12;    // edge strength at which the weight crosses 0.5 (pre-normalisation)
    // Plain (non-const) floats on purpose: every GLSL compiler folds them, and no driver can
    // reject the program over constant-expression rules for exp().
    float lo = 1.0 / (1.0 + exp( k * mid));           // sigmoid at edge = 0
    float hi = 1.0 / (1.0 + exp(-k * (0.5 - mid)));   // sigmoid at edge = 0.5
    float s = 1.0 / (1.0 + exp(-k * (edge - mid)));
    return clamp((s - lo) / (hi - lo), 0.0, 1.0);
}

void main() {
    // ── 1. Single 9-tap neighbourhood fetch ─────────────────────────────────
    highp vec2 o = u_texelSize;
    vec3 c5 = texture(u_texture, v_texCoord).rgb;
    vec3 c1 = texture(u_texture, v_texCoord + vec2(-o.x, -o.y)).rgb;
    vec3 c2 = texture(u_texture, v_texCoord + vec2( 0.0, -o.y)).rgb;
    vec3 c3 = texture(u_texture, v_texCoord + vec2( o.x, -o.y)).rgb;
    vec3 c4 = texture(u_texture, v_texCoord + vec2(-o.x,  0.0)).rgb;
    vec3 c6 = texture(u_texture, v_texCoord + vec2( o.x,  0.0)).rgb;
    vec3 c7 = texture(u_texture, v_texCoord + vec2(-o.x,  o.y)).rgb;
    vec3 c8 = texture(u_texture, v_texCoord + vec2( 0.0,  o.y)).rgb;
    vec3 c9 = texture(u_texture, v_texCoord + vec2( o.x,  o.y)).rgb;

    vec3 color = c5;

    // ── 2. 3x3 luma-weighted denoise ─────────────────────────────────────────
    // Range-only weighting (luma difference); there is no spatial Gaussian term, so this is
    // NOT a true bilateral filter.
    if (u_enableDenoise) {
        float l5 = getLuma(color);
        vec3 accum = color;
        float wSum = 1.0;

        // Unrolled loop — avoids dynamic indexing on PowerVR
        // Falloff constant: strength 0.0 -> 20.0 (mild, edge-preserving),
        // strength 1.0 -> 4.0 (aggressive smoothing). Smaller constant = weight
        // stays higher across larger luma deltas = more blending = stronger denoise.
        float falloff = mix(20.0, 4.0, clamp(u_denoiseStrength, 0.0, 1.0));
        float d; float w;

        d = abs(getLuma(c1) - l5); w = exp(-d * falloff); accum += c1 * w; wSum += w;
        d = abs(getLuma(c2) - l5); w = exp(-d * falloff); accum += c2 * w; wSum += w;
        d = abs(getLuma(c3) - l5); w = exp(-d * falloff); accum += c3 * w; wSum += w;
        d = abs(getLuma(c4) - l5); w = exp(-d * falloff); accum += c4 * w; wSum += w;
        d = abs(getLuma(c6) - l5); w = exp(-d * falloff); accum += c6 * w; wSum += w;
        d = abs(getLuma(c7) - l5); w = exp(-d * falloff); accum += c7 * w; wSum += w;
        d = abs(getLuma(c8) - l5); w = exp(-d * falloff); accum += c8 * w; wSum += w;
        d = abs(getLuma(c9) - l5); w = exp(-d * falloff); accum += c9 * w; wSum += w;

        color = accum / wSum;
    }

    // ── 3. Line darken (Anime4K-inspired heuristic, not the Anime4K algorithm) ──
    // Pulls dark pixels toward their darkest 8-neighbour below luma 0.6, capped at 35% blend.
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

    // ── 4. Sharpening — three independent, stackable filters ─────────────────
    if (u_enableRcasUsm && u_rcasUsmIntensity > 0.0) {
        // RCAS-style local min/max clamp + unsharp-mask delta
        vec3 mn  = min(min(min(c2, c4), min(c6, c8)), c5);
        vec3 mx  = max(max(max(c2, c4), max(c6, c8)), c5);
        vec3 blur = (c2 + c4 + c6 + c8) * 0.25;
        vec3 usm  = color - blur;
        vec3 lim  = min(color - mn, mx - color);
        vec3 delta = clamp(usm * (1.0 + u_rcasUsmIntensity * 2.0), -lim, lim);
        color = clamp(color + delta, 0.0, 1.0);
    }

    if (u_enableAdaptiveSmoothstep && u_adaptiveSmoothstepIntensity > 0.0) {
        // Adaptive-Sharpen, smoothstep (cubic Hermite) edge weight
        float lC    = getLuma(color);
        float lBlur = (getLuma(c2) + getLuma(c4) + getLuma(c6) + getLuma(c8)) * 0.25;
        float edgeD = abs(lC - lBlur);
        float adaptW = smoothstep(0.02, 0.25, edgeD) * u_adaptiveSmoothstepIntensity;
        color = clamp(color + (color - vec3(lBlur)) * adaptW * 1.5, 0.0, 1.0);
    }

    if (u_enableAdaptiveSigmoid && u_adaptiveSigmoidIntensity > 0.0) {
        // Adaptive-Sharpen, true logistic-sigmoid edge weight
        float lC    = getLuma(color);
        float lBlur = (getLuma(c2) + getLuma(c4) + getLuma(c6) + getLuma(c8)) * 0.25;
        float edgeD = abs(lC - lBlur);
        float adaptW = sigmoidEdgeWeight(edgeD) * u_adaptiveSigmoidIntensity;
        color = clamp(color + (color - vec3(lBlur)) * adaptW * 1.5, 0.0, 1.0);
    }

    // ── 5. Vibrance / S-Curve ────────────────────────────────────────────────
    // Deliberate RGB-space approximation (not HSL vibrance): smoothstep-polynomial S-curve
    // plus a selective saturation boost weighted inversely by the current max-min RGB gap.
    if (u_enableVibrance) {
        vec3 base = color;
        // Smooth S-curve contrast
        vec3 curved = base * base * (3.0 - 2.0 * base);
        // Selective vibrance (boosts muted colours only)
        float maxC = max(curved.r, max(curved.g, curved.b));
        float minC = min(curved.r, min(curved.g, curved.b));
        float sat  = maxC - minC;
        float luma = getLuma(curved);
        float vibrAmt = 0.25 * (1.0 - sat);
        vec3 boosted = mix(vec3(luma), curved, 1.0 + vibrAmt);
        // u_vibranceIntensity blends the whole effect back toward the untouched
        // source colour, giving the slider a real, continuous magnitude instead
        // of a bare on/off switch.
        color = mix(base, boosted, clamp(u_vibranceIntensity, 0.0, 1.0));
    }

    fragColor = vec4(color, 1.0);
}
