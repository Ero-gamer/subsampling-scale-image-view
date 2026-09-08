#version 300 es
precision mediump float;

in vec2 v_texCoord;
out vec4 fragColor;

uniform sampler2D u_texture;
uniform vec2      u_texelSize;     // (1.0/width, 1.0/height)

uniform bool  u_enableDenoise;     // Bilateral Denoise
uniform bool  u_enableDarken;      // Anime4K Line Darken
uniform bool  u_enableVibrance;    // Vibrance / S-Curve
uniform int   u_sharpenMode;       // 0=Off  1=RCAS+USM  2=Adaptive
uniform float u_sharpness;         // 0.0..1.0

float getLuma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    // ── 1. Single 9-tap neighbourhood fetch ─────────────────────────────────
    vec2 o = u_texelSize;
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

    // ── 2. Light Bilateral Denoise ───────────────────────────────────────────
    if (u_enableDenoise) {
        float l5 = getLuma(c5);
        vec3 accum = c5;
        float wSum = 1.0;

        // Unrolled loop — avoids dynamic indexing on PowerVR
        float d; float w;

        d = abs(getLuma(c1) - l5); w = exp(-d * 12.0); accum += c1 * w; wSum += w;
        d = abs(getLuma(c2) - l5); w = exp(-d * 12.0); accum += c2 * w; wSum += w;
        d = abs(getLuma(c3) - l5); w = exp(-d * 12.0); accum += c3 * w; wSum += w;
        d = abs(getLuma(c4) - l5); w = exp(-d * 12.0); accum += c4 * w; wSum += w;
        d = abs(getLuma(c6) - l5); w = exp(-d * 12.0); accum += c6 * w; wSum += w;
        d = abs(getLuma(c7) - l5); w = exp(-d * 12.0); accum += c7 * w; wSum += w;
        d = abs(getLuma(c8) - l5); w = exp(-d * 12.0); accum += c8 * w; wSum += w;
        d = abs(getLuma(c9) - l5); w = exp(-d * 12.0); accum += c9 * w; wSum += w;

        color = accum / wSum;
    }

    // ── 3. Anime4K Line Darken ───────────────────────────────────────────────
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

    // ── 4. Sharpening ────────────────────────────────────────────────────────
    if (u_sharpenMode == 1 && u_sharpness > 0.0) {
        // RCAS + USM hybrid
        vec3 mn  = min(min(min(c2, c4), min(c6, c8)), c5);
        vec3 mx  = max(max(max(c2, c4), max(c6, c8)), c5);
        vec3 blur = (c2 + c4 + c6 + c8) * 0.25;
        vec3 usm  = color - blur;
        vec3 lim  = min(color - mn, mx - color);
        vec3 delta = clamp(usm * (1.0 + u_sharpness * 2.0), -lim, lim);
        color = clamp(color + delta, 0.0, 1.0);

    } else if (u_sharpenMode == 2 && u_sharpness > 0.0) {
        // Adaptive-Sharpen (sigmoid edge-aware)
        float lC   = getLuma(color);
        float lBlur = (getLuma(c2) + getLuma(c4) + getLuma(c6) + getLuma(c8)) * 0.25;
        float edgeD = abs(lC - lBlur);
        float adaptW = smoothstep(0.02, 0.25, edgeD) * u_sharpness;
        color = clamp(color + (color - vec3(lBlur)) * adaptW * 1.5, 0.0, 1.0);
    }

    // ── 5. Vibrance / S-Curve ────────────────────────────────────────────────
    if (u_enableVibrance) {
        // Smooth S-curve contrast
        color = color * color * (3.0 - 2.0 * color);
        // Selective vibrance (boosts muted colours only)
        float maxC = max(color.r, max(color.g, color.b));
        float minC = min(color.r, min(color.g, color.b));
        float sat  = maxC - minC;
        float luma = getLuma(color);
        float vibrAmt = 0.25 * (1.0 - sat);
        color = mix(vec3(luma), color, 1.0 + vibrAmt);
    }

    fragColor = vec4(color, 1.0);
}
