#version 300 es
// RCAS (Robust Contrast Adaptive Sharpening) from AMD FidelityFX Super Resolution 1.0,
// ffx_fsr1.h "FsrRcasF" (Copyright (c) 2021 Advanced Micro Devices, Inc., MIT licence).
// 5-tap cross; the optional FSR_RCAS_DENOISE path is not enabled (AMD's default), and neither
// is the alpha pass-through.
//
// AMD's C-like code relies on GPU max() dropping a NaN operand when a channel is flat at exactly
// 0 or exactly 1 (0*rcp(0), 0/0). GLSL leaves that undefined, so both cases are handled
// explicitly and branch-free below: a degenerate limiter is pushed to a huge value so the
// other limiter wins, which is what the dropped-NaN behaviour produces.
precision highp float;
precision highp int;

in highp vec2 v_texCoord;
out vec4 fragColor;

uniform highp sampler2D u_texture;
uniform highp vec2 u_texelSize;
uniform float u_rcasCon;   // FsrRcasCon: exp2(-stops); 1.0 = maximum sharpness

const float FSR_RCAS_LIMIT = 0.25 - (1.0 / 16.0);

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
    //    b
    //  d e f
    //    h
    highp vec2 o = u_texelSize;
    vec3 b = texture(u_texture, v_texCoord + vec2( 0.0, -o.y)).rgb;
    vec3 d = texture(u_texture, v_texCoord + vec2(-o.x,  0.0)).rgb;
    vec3 e = texture(u_texture, v_texCoord).rgb;
    vec3 f = texture(u_texture, v_texCoord + vec2( o.x,  0.0)).rgb;
    vec3 h = texture(u_texture, v_texCoord + vec2( 0.0,  o.y)).rgb;

    // Min and max of ring.
    vec3 mn4 = min(min(min(b, d), f), h);
    vec3 mx4 = max(max(max(b, d), f), h);

    // Limiters.
    vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1e-6));
    hitMin += step(mx4, vec3(0.0)) * 1.0e4;                       // flat-at-0 channel: no low limit
    vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * mn4 - 4.0, vec3(-1e-6));
    hitMax -= step(vec3(1.0), mn4) * 1.0e4;                        // flat-at-1 channel: no high limit
    vec3 lobe3 = max(-hitMin, hitMax);
    float lobe = max(-FSR_RCAS_LIMIT, min(max(lobe3.r, max(lobe3.g, lobe3.b)), 0.0)) * u_rcasCon;

    // Resolve.
    float rcpL = 1.0 / (4.0 * lobe + 1.0);
    vec3 pix = (lobe * b + lobe * d + lobe * h + lobe * f + e) * rcpL;
    pix = clamp(pix, 0.0, 1.0);
    fragColor = vec4(applyVibranceTail(pix), 1.0);
}
