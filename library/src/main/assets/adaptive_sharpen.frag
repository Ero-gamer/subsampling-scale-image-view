#version 300 es
// Adaptive-Sharpen, pass two (sharpen). Port of bacondither/Adaptive-sharpen, version
// 2018-04-14, "Adaptive-sharpen - Pass two.hlsl". Constants are the original's, unchanged.
//
// Copyright (c) 2015-2018, bacondither. All rights reserved.
// Redistribution and use in source and binary forms, with or without modification, are
// permitted provided that the following conditions are met:
// 1. Redistributions of source code must retain the above copyright notice, this list of
//    conditions and the following disclaimer in this position and unchanged.
// 2. Redistributions in binary form must reproduce the above copyright notice, this list of
//    conditions and the following disclaimer in the documentation and/or other materials
//    provided with the distribution.
// THIS SOFTWARE IS PROVIDED BY THE AUTHORS ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES,
// INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
// PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT,
// INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
// BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
// STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
// USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
//
// Input: the RGBA16F texture written by adaptive_edge.frag (rgb = colour, a = edge strength).
// Differences from the original (all deliberate, none change the maths):
//  - the edge value is stored WITHOUT the original's +2.0 offset (a_offset), so every
//    "x - a_offset" of the original is simply "x" here; the offset only existed to detect a
//    truncated alpha channel (its green-screen bounds check), which cannot happen here;
//  - every division that could be x/0 has its divisor clamped to a tiny positive value, which
//    yields the same +inf -> min() result the HLSL relies on, without undefined GLSL behaviour;
//  - u_curveHeight is the original's curve_height (its main strength control).
precision highp float;
precision highp int;

in highp vec2 v_texCoord;
out vec4 fragColor;

uniform highp sampler2D u_texture;
uniform highp vec2 u_texelSize;
uniform float u_curveHeight;      // original curve_height; ~0.3..2.0 is the author's sane range

const float curveslope   = 0.5;
const float L_overshoot  = 0.003;
const float L_compr_low  = 0.167;
const float L_compr_high = 0.334;
const float D_overshoot  = 0.009;
const float D_compr_low  = 0.250;
const float D_compr_high = 0.500;
const float scale_lim    = 0.1;
const float scale_cs     = 0.056;
const float dW_lothr     = 0.3;
const float dW_hithr     = 0.8;
const float lowthr_mxw   = 0.1;
const float pm_p         = 0.7;

// Soft if, fast linear approx
float soft_if(float a, float b, float c, float maxedge) {
    return clamp((a + b + c + 0.056) / (abs(maxedge) + 0.03) - 0.85, 0.0, 1.0);
}
// Soft limit, modified tanh
float soft_lim(float v, float s) {
    float e = exp(2.0 * min(abs(v), s * 24.0) / s);
    return (e - 1.0) / (e + 1.0) * s;
}
// Weighted power mean
float wpmean(float a, float b, float w) {
    return pow(w * pow(abs(a), pm_p) + abs(1.0 - w) * pow(abs(b), pm_p), 1.0 / pm_p);
}
// Colour to luma, fast approx gamma, avg of rec. 709 & 601 luma coeffs
float CtL(vec3 rgb) {
    return sqrt(dot(vec3(0.2558, 0.6511, 0.0931), clamp(rgb * abs(rgb), 0.0, 1.0)));
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
    highp vec2 p1 = u_texelSize;
    highp vec2 tex = v_texCoord;
    vec4 orig = texture(u_texture, tex);
    float c_edge = orig.a;
    vec4 c[25];
    c[0] = vec4(clamp(orig.rgb, 0.0, 1.0), orig.a);
    c[1] = texture(u_texture, tex + p1 * vec2(-1.0, -1.0));
    c[2] = texture(u_texture, tex + p1 * vec2(0.0, -1.0));
    c[3] = texture(u_texture, tex + p1 * vec2(1.0, -1.0));
    c[4] = texture(u_texture, tex + p1 * vec2(-1.0, 0.0));
    c[5] = texture(u_texture, tex + p1 * vec2(1.0, 0.0));
    c[6] = texture(u_texture, tex + p1 * vec2(-1.0, 1.0));
    c[7] = texture(u_texture, tex + p1 * vec2(0.0, 1.0));
    c[8] = texture(u_texture, tex + p1 * vec2(1.0, 1.0));
    c[9] = texture(u_texture, tex + p1 * vec2(0.0, -2.0));
    c[10] = texture(u_texture, tex + p1 * vec2(-2.0, 0.0));
    c[11] = texture(u_texture, tex + p1 * vec2(2.0, 0.0));
    c[12] = texture(u_texture, tex + p1 * vec2(0.0, 2.0));
    c[13] = texture(u_texture, tex + p1 * vec2(0.0, 3.0));
    c[14] = texture(u_texture, tex + p1 * vec2(1.0, 2.0));
    c[15] = texture(u_texture, tex + p1 * vec2(-1.0, 2.0));
    c[16] = texture(u_texture, tex + p1 * vec2(3.0, 0.0));
    c[17] = texture(u_texture, tex + p1 * vec2(2.0, 1.0));
    c[18] = texture(u_texture, tex + p1 * vec2(2.0, -1.0));
    c[19] = texture(u_texture, tex + p1 * vec2(-3.0, 0.0));
    c[20] = texture(u_texture, tex + p1 * vec2(-2.0, 1.0));
    c[21] = texture(u_texture, tex + p1 * vec2(-2.0, -1.0));
    c[22] = texture(u_texture, tex + p1 * vec2(0.0, -3.0));
    c[23] = texture(u_texture, tex + p1 * vec2(1.0, -2.0));
    c[24] = texture(u_texture, tex + p1 * vec2(-1.0, -2.0));

    float maxedge = max(max(max(c[1].a, c[2].a), max(c[3].a, c[4].a)),
                        max(max(c[5].a, c[6].a), max(c[7].a, c[8].a)));
    maxedge = max(max(maxedge, max(max(c[9].a, c[10].a), max(c[11].a, c[12].a))), c[0].a);

    float sbe = soft_if(c[2].a, c[9].a, c[22].a, maxedge) * soft_if(c[7].a, c[12].a, c[13].a, maxedge)
              + soft_if(c[4].a, c[10].a, c[19].a, maxedge) * soft_if(c[5].a, c[11].a, c[16].a, maxedge)
              + soft_if(c[1].a, c[24].a, c[21].a, maxedge) * soft_if(c[8].a, c[14].a, c[17].a, maxedge)
              + soft_if(c[3].a, c[23].a, c[18].a, maxedge) * soft_if(c[6].a, c[20].a, c[15].a, maxedge);
    vec2 cs = mix(vec2(L_compr_low, D_compr_low), vec2(L_compr_high, D_compr_high),
                  smoothstep(2.0, 3.1, sbe));
    float c0_Y = CtL(c[0].rgb);
    float luma[25];
    luma[0] = c0_Y;
    luma[1] = CtL(c[1].rgb);
    luma[2] = CtL(c[2].rgb);
    luma[3] = CtL(c[3].rgb);
    luma[4] = CtL(c[4].rgb);
    luma[5] = CtL(c[5].rgb);
    luma[6] = CtL(c[6].rgb);
    luma[7] = CtL(c[7].rgb);
    luma[8] = CtL(c[8].rgb);
    luma[9] = CtL(c[9].rgb);
    luma[10] = CtL(c[10].rgb);
    luma[11] = CtL(c[11].rgb);
    luma[12] = CtL(c[12].rgb);
    luma[13] = CtL(c[13].rgb);
    luma[14] = CtL(c[14].rgb);
    luma[15] = CtL(c[15].rgb);
    luma[16] = CtL(c[16].rgb);
    luma[17] = CtL(c[17].rgb);
    luma[18] = CtL(c[18].rgb);
    luma[19] = CtL(c[19].rgb);
    luma[20] = CtL(c[20].rgb);
    luma[21] = CtL(c[21].rgb);
    luma[22] = CtL(c[22].rgb);
    luma[23] = CtL(c[23].rgb);
    luma[24] = CtL(c[24].rgb);

    const vec3 W1 = vec3(0.5,           1.0, 1.41421356237);
    const vec3 W2 = vec3(0.86602540378, 1.0, 0.54772255751);
    vec3 dW = mix(W1, W2, smoothstep(dW_lothr, dW_hithr, c_edge));
    dW = dW * dW;

    float mdiff_c0 = 0.02 + 3.0 * (abs(luma[0]-luma[2]) + abs(luma[0]-luma[4]) + abs(luma[0]-luma[5]) + abs(luma[0]-luma[7])
        + 0.25 * (abs(luma[0]-luma[1]) + abs(luma[0]-luma[3]) + abs(luma[0]-luma[6]) + abs(luma[0]-luma[8])));
    float weights[12];
    weights[0] = min(mdiff_c0 / max(abs(luma[1]-luma[24]) + abs(luma[1]-luma[21]) + abs(luma[1]-luma[2]) + abs(luma[1]-luma[4]) + 0.5 * (abs(luma[1]-luma[9]) + abs(luma[1]-luma[10])), 1e-9), dW.y);
    weights[1] = dW.x;
    weights[2] = min(mdiff_c0 / max(abs(luma[3]-luma[23]) + abs(luma[3]-luma[18]) + abs(luma[3]-luma[5]) + abs(luma[3]-luma[2]) + 0.5 * (abs(luma[3]-luma[9]) + abs(luma[3]-luma[11])), 1e-9), dW.y);
    weights[3] = dW.x;
    weights[4] = dW.x;
    weights[5] = min(mdiff_c0 / max(abs(luma[6]-luma[4]) + abs(luma[6]-luma[20]) + abs(luma[6]-luma[15]) + abs(luma[6]-luma[7]) + 0.5 * (abs(luma[6]-luma[10]) + abs(luma[6]-luma[12])), 1e-9), dW.y);
    weights[6] = dW.x;
    weights[7] = min(mdiff_c0 / max(abs(luma[8]-luma[5]) + abs(luma[8]-luma[7]) + abs(luma[8]-luma[17]) + abs(luma[8]-luma[14]) + 0.5 * (abs(luma[8]-luma[12]) + abs(luma[8]-luma[11])), 1e-9), dW.y);
    weights[8] = min(mdiff_c0 / max(abs(luma[9]-luma[2]) + abs(luma[9]-luma[24]) + abs(luma[9]-luma[23]) + abs(luma[9]-luma[22]) + 0.5 * (abs(luma[9]-luma[1]) + abs(luma[9]-luma[3])), 1e-9), dW.z);
    weights[9] = min(mdiff_c0 / max(abs(luma[10]-luma[20]) + abs(luma[10]-luma[19]) + abs(luma[10]-luma[21]) + abs(luma[10]-luma[4]) + 0.5 * (abs(luma[10]-luma[1]) + abs(luma[10]-luma[6])), 1e-9), dW.z);
    weights[10] = min(mdiff_c0 / max(abs(luma[11]-luma[17]) + abs(luma[11]-luma[5]) + abs(luma[11]-luma[18]) + abs(luma[11]-luma[16]) + 0.5 * (abs(luma[11]-luma[3]) + abs(luma[11]-luma[8])), 1e-9), dW.z);
    weights[11] = min(mdiff_c0 / max(abs(luma[12]-luma[13]) + abs(luma[12]-luma[15]) + abs(luma[12]-luma[7]) + abs(luma[12]-luma[14]) + 0.5 * (abs(luma[12]-luma[6]) + abs(luma[12]-luma[8])), 1e-9), dW.z);
    weights[0] = (max(max((weights[8] + weights[9]) / 4.0, weights[0]), 0.25) + weights[0]) / 2.0;
    weights[2] = (max(max((weights[8] + weights[10]) / 4.0, weights[2]), 0.25) + weights[2]) / 2.0;
    weights[5] = (max(max((weights[9] + weights[11]) / 4.0, weights[5]), 0.25) + weights[5]) / 2.0;
    weights[7] = (max(max((weights[10] + weights[11]) / 4.0, weights[7]), 0.25) + weights[7]) / 2.0;
    float lowthrsum = 0.0; float weightsum = 0.0; float neg_laplace = 0.0;
    {
        float t = clamp((c[1].a - 0.01) / (lowthr_mxw - 0.01), 0.0, 1.0);
        float lowthr = t * t * (2.97 - 1.98 * t) + 0.01;
        neg_laplace += pow(luma[1] + 0.06, 2.4) * (weights[0] * lowthr);
        weightsum += weights[0] * lowthr;
        lowthrsum += lowthr / 12.0;
    }
    {
        float t = clamp((c[2].a - 0.01) / (lowthr_mxw - 0.01), 0.0, 1.0);
        float lowthr = t * t * (2.97 - 1.98 * t) + 0.01;
        neg_laplace += pow(luma[2] + 0.06, 2.4) * (weights[1] * lowthr);
        weightsum += weights[1] * lowthr;
        lowthrsum += lowthr / 12.0;
    }
    {
        float t = clamp((c[3].a - 0.01) / (lowthr_mxw - 0.01), 0.0, 1.0);
        float lowthr = t * t * (2.97 - 1.98 * t) + 0.01;
        neg_laplace += pow(luma[3] + 0.06, 2.4) * (weights[2] * lowthr);
        weightsum += weights[2] * lowthr;
        lowthrsum += lowthr / 12.0;
    }
    {
        float t = clamp((c[4].a - 0.01) / (lowthr_mxw - 0.01), 0.0, 1.0);
        float lowthr = t * t * (2.97 - 1.98 * t) + 0.01;
        neg_laplace += pow(luma[4] + 0.06, 2.4) * (weights[3] * lowthr);
        weightsum += weights[3] * lowthr;
        lowthrsum += lowthr / 12.0;
    }
    {
        float t = clamp((c[5].a - 0.01) / (lowthr_mxw - 0.01), 0.0, 1.0);
        float lowthr = t * t * (2.97 - 1.98 * t) + 0.01;
        neg_laplace += pow(luma[5] + 0.06, 2.4) * (weights[4] * lowthr);
        weightsum += weights[4] * lowthr;
        lowthrsum += lowthr / 12.0;
    }
    {
        float t = clamp((c[6].a - 0.01) / (lowthr_mxw - 0.01), 0.0, 1.0);
        float lowthr = t * t * (2.97 - 1.98 * t) + 0.01;
        neg_laplace += pow(luma[6] + 0.06, 2.4) * (weights[5] * lowthr);
        weightsum += weights[5] * lowthr;
        lowthrsum += lowthr / 12.0;
    }
    {
        float t = clamp((c[7].a - 0.01) / (lowthr_mxw - 0.01), 0.0, 1.0);
        float lowthr = t * t * (2.97 - 1.98 * t) + 0.01;
        neg_laplace += pow(luma[7] + 0.06, 2.4) * (weights[6] * lowthr);
        weightsum += weights[6] * lowthr;
        lowthrsum += lowthr / 12.0;
    }
    {
        float t = clamp((c[8].a - 0.01) / (lowthr_mxw - 0.01), 0.0, 1.0);
        float lowthr = t * t * (2.97 - 1.98 * t) + 0.01;
        neg_laplace += pow(luma[8] + 0.06, 2.4) * (weights[7] * lowthr);
        weightsum += weights[7] * lowthr;
        lowthrsum += lowthr / 12.0;
    }
    {
        float t = clamp((c[9].a - 0.01) / (lowthr_mxw - 0.01), 0.0, 1.0);
        float lowthr = t * t * (2.97 - 1.98 * t) + 0.01;
        neg_laplace += pow(luma[9] + 0.06, 2.4) * (weights[8] * lowthr);
        weightsum += weights[8] * lowthr;
        lowthrsum += lowthr / 12.0;
    }
    {
        float t = clamp((c[10].a - 0.01) / (lowthr_mxw - 0.01), 0.0, 1.0);
        float lowthr = t * t * (2.97 - 1.98 * t) + 0.01;
        neg_laplace += pow(luma[10] + 0.06, 2.4) * (weights[9] * lowthr);
        weightsum += weights[9] * lowthr;
        lowthrsum += lowthr / 12.0;
    }
    {
        float t = clamp((c[11].a - 0.01) / (lowthr_mxw - 0.01), 0.0, 1.0);
        float lowthr = t * t * (2.97 - 1.98 * t) + 0.01;
        neg_laplace += pow(luma[11] + 0.06, 2.4) * (weights[10] * lowthr);
        weightsum += weights[10] * lowthr;
        lowthrsum += lowthr / 12.0;
    }
    {
        float t = clamp((c[12].a - 0.01) / (lowthr_mxw - 0.01), 0.0, 1.0);
        float lowthr = t * t * (2.97 - 1.98 * t) + 0.01;
        neg_laplace += pow(luma[12] + 0.06, 2.4) * (weights[11] * lowthr);
        weightsum += weights[11] * lowthr;
        lowthrsum += lowthr / 12.0;
    }
    neg_laplace = pow(abs(neg_laplace / weightsum), 1.0 / 2.4) - 0.06;
    float sharpen_val = u_curveHeight / (u_curveHeight * curveslope * pow(abs(c_edge), 3.5) + 0.625);
    float sharpdiff = (c0_Y - neg_laplace) * (lowthrsum * sharpen_val + 0.01);
    float temp;
    temp = luma[0]; luma[0] = min(luma[0], luma[1]); luma[1] = max(temp, luma[1]);
    temp = luma[2]; luma[2] = min(luma[2], luma[3]); luma[3] = max(temp, luma[3]);
    temp = luma[4]; luma[4] = min(luma[4], luma[5]); luma[5] = max(temp, luma[5]);
    temp = luma[6]; luma[6] = min(luma[6], luma[7]); luma[7] = max(temp, luma[7]);
    temp = luma[8]; luma[8] = min(luma[8], luma[9]); luma[9] = max(temp, luma[9]);
    temp = luma[10]; luma[10] = min(luma[10], luma[11]); luma[11] = max(temp, luma[11]);
    temp = luma[12]; luma[12] = min(luma[12], luma[13]); luma[13] = max(temp, luma[13]);
    temp = luma[14]; luma[14] = min(luma[14], luma[15]); luma[15] = max(temp, luma[15]);
    temp = luma[16]; luma[16] = min(luma[16], luma[17]); luma[17] = max(temp, luma[17]);
    temp = luma[18]; luma[18] = min(luma[18], luma[19]); luma[19] = max(temp, luma[19]);
    temp = luma[20]; luma[20] = min(luma[20], luma[21]); luma[21] = max(temp, luma[21]);
    temp = luma[22]; luma[22] = min(luma[22], luma[23]); luma[23] = max(temp, luma[23]);
    temp = luma[0]; luma[0] = min(luma[0], luma[24]); luma[24] = max(temp, luma[24]);
    temp = luma[24]; luma[24] = max(luma[24], luma[23]); luma[23] = min(temp, luma[23]);
    temp = luma[0]; luma[0] = min(luma[0], luma[22]); luma[22] = max(temp, luma[22]);
    temp = luma[24]; luma[24] = max(luma[24], luma[21]); luma[21] = min(temp, luma[21]);
    temp = luma[0]; luma[0] = min(luma[0], luma[20]); luma[20] = max(temp, luma[20]);
    temp = luma[24]; luma[24] = max(luma[24], luma[19]); luma[19] = min(temp, luma[19]);
    temp = luma[0]; luma[0] = min(luma[0], luma[18]); luma[18] = max(temp, luma[18]);
    temp = luma[24]; luma[24] = max(luma[24], luma[17]); luma[17] = min(temp, luma[17]);
    temp = luma[0]; luma[0] = min(luma[0], luma[16]); luma[16] = max(temp, luma[16]);
    temp = luma[24]; luma[24] = max(luma[24], luma[15]); luma[15] = min(temp, luma[15]);
    temp = luma[0]; luma[0] = min(luma[0], luma[14]); luma[14] = max(temp, luma[14]);
    temp = luma[24]; luma[24] = max(luma[24], luma[13]); luma[13] = min(temp, luma[13]);
    temp = luma[0]; luma[0] = min(luma[0], luma[12]); luma[12] = max(temp, luma[12]);
    temp = luma[24]; luma[24] = max(luma[24], luma[11]); luma[11] = min(temp, luma[11]);
    temp = luma[0]; luma[0] = min(luma[0], luma[10]); luma[10] = max(temp, luma[10]);
    temp = luma[24]; luma[24] = max(luma[24], luma[9]); luma[9] = min(temp, luma[9]);
    temp = luma[0]; luma[0] = min(luma[0], luma[8]); luma[8] = max(temp, luma[8]);
    temp = luma[24]; luma[24] = max(luma[24], luma[7]); luma[7] = min(temp, luma[7]);
    temp = luma[0]; luma[0] = min(luma[0], luma[6]); luma[6] = max(temp, luma[6]);
    temp = luma[24]; luma[24] = max(luma[24], luma[5]); luma[5] = min(temp, luma[5]);
    temp = luma[0]; luma[0] = min(luma[0], luma[4]); luma[4] = max(temp, luma[4]);
    temp = luma[24]; luma[24] = max(luma[24], luma[3]); luma[3] = min(temp, luma[3]);
    temp = luma[0]; luma[0] = min(luma[0], luma[2]); luma[2] = max(temp, luma[2]);
    temp = luma[24]; luma[24] = max(luma[24], luma[1]); luma[1] = min(temp, luma[1]);
    temp = luma[1]; luma[1] = min(luma[1], luma[2]); luma[2] = max(temp, luma[2]);
    temp = luma[3]; luma[3] = min(luma[3], luma[4]); luma[4] = max(temp, luma[4]);
    temp = luma[5]; luma[5] = min(luma[5], luma[6]); luma[6] = max(temp, luma[6]);
    temp = luma[7]; luma[7] = min(luma[7], luma[8]); luma[8] = max(temp, luma[8]);
    temp = luma[9]; luma[9] = min(luma[9], luma[10]); luma[10] = max(temp, luma[10]);
    temp = luma[11]; luma[11] = min(luma[11], luma[12]); luma[12] = max(temp, luma[12]);
    temp = luma[13]; luma[13] = min(luma[13], luma[14]); luma[14] = max(temp, luma[14]);
    temp = luma[15]; luma[15] = min(luma[15], luma[16]); luma[16] = max(temp, luma[16]);
    temp = luma[17]; luma[17] = min(luma[17], luma[18]); luma[18] = max(temp, luma[18]);
    temp = luma[19]; luma[19] = min(luma[19], luma[20]); luma[20] = max(temp, luma[20]);
    temp = luma[21]; luma[21] = min(luma[21], luma[22]); luma[22] = max(temp, luma[22]);
    temp = luma[1]; luma[1] = min(luma[1], luma[23]); luma[23] = max(temp, luma[23]);
    temp = luma[23]; luma[23] = max(luma[23], luma[22]); luma[22] = min(temp, luma[22]);
    temp = luma[1]; luma[1] = min(luma[1], luma[21]); luma[21] = max(temp, luma[21]);
    temp = luma[23]; luma[23] = max(luma[23], luma[20]); luma[20] = min(temp, luma[20]);
    temp = luma[1]; luma[1] = min(luma[1], luma[19]); luma[19] = max(temp, luma[19]);
    temp = luma[23]; luma[23] = max(luma[23], luma[18]); luma[18] = min(temp, luma[18]);
    temp = luma[1]; luma[1] = min(luma[1], luma[17]); luma[17] = max(temp, luma[17]);
    temp = luma[23]; luma[23] = max(luma[23], luma[16]); luma[16] = min(temp, luma[16]);
    temp = luma[1]; luma[1] = min(luma[1], luma[15]); luma[15] = max(temp, luma[15]);
    temp = luma[23]; luma[23] = max(luma[23], luma[14]); luma[14] = min(temp, luma[14]);
    temp = luma[1]; luma[1] = min(luma[1], luma[13]); luma[13] = max(temp, luma[13]);
    temp = luma[23]; luma[23] = max(luma[23], luma[12]); luma[12] = min(temp, luma[12]);
    temp = luma[1]; luma[1] = min(luma[1], luma[11]); luma[11] = max(temp, luma[11]);
    temp = luma[23]; luma[23] = max(luma[23], luma[10]); luma[10] = min(temp, luma[10]);
    temp = luma[1]; luma[1] = min(luma[1], luma[9]); luma[9] = max(temp, luma[9]);
    temp = luma[23]; luma[23] = max(luma[23], luma[8]); luma[8] = min(temp, luma[8]);
    temp = luma[1]; luma[1] = min(luma[1], luma[7]); luma[7] = max(temp, luma[7]);
    temp = luma[23]; luma[23] = max(luma[23], luma[6]); luma[6] = min(temp, luma[6]);
    temp = luma[1]; luma[1] = min(luma[1], luma[5]); luma[5] = max(temp, luma[5]);
    temp = luma[23]; luma[23] = max(luma[23], luma[4]); luma[4] = min(temp, luma[4]);
    temp = luma[1]; luma[1] = min(luma[1], luma[3]); luma[3] = max(temp, luma[3]);
    temp = luma[23]; luma[23] = max(luma[23], luma[2]); luma[2] = min(temp, luma[2]);
    temp = luma[2]; luma[2] = min(luma[2], luma[3]); luma[3] = max(temp, luma[3]);
    temp = luma[4]; luma[4] = min(luma[4], luma[5]); luma[5] = max(temp, luma[5]);
    temp = luma[6]; luma[6] = min(luma[6], luma[7]); luma[7] = max(temp, luma[7]);
    temp = luma[8]; luma[8] = min(luma[8], luma[9]); luma[9] = max(temp, luma[9]);
    temp = luma[10]; luma[10] = min(luma[10], luma[11]); luma[11] = max(temp, luma[11]);
    temp = luma[12]; luma[12] = min(luma[12], luma[13]); luma[13] = max(temp, luma[13]);
    temp = luma[14]; luma[14] = min(luma[14], luma[15]); luma[15] = max(temp, luma[15]);
    temp = luma[16]; luma[16] = min(luma[16], luma[17]); luma[17] = max(temp, luma[17]);
    temp = luma[18]; luma[18] = min(luma[18], luma[19]); luma[19] = max(temp, luma[19]);
    temp = luma[20]; luma[20] = min(luma[20], luma[21]); luma[21] = max(temp, luma[21]);
    temp = luma[2]; luma[2] = min(luma[2], luma[22]); luma[22] = max(temp, luma[22]);
    temp = luma[22]; luma[22] = max(luma[22], luma[21]); luma[21] = min(temp, luma[21]);
    temp = luma[2]; luma[2] = min(luma[2], luma[20]); luma[20] = max(temp, luma[20]);
    temp = luma[22]; luma[22] = max(luma[22], luma[19]); luma[19] = min(temp, luma[19]);
    temp = luma[2]; luma[2] = min(luma[2], luma[18]); luma[18] = max(temp, luma[18]);
    temp = luma[22]; luma[22] = max(luma[22], luma[17]); luma[17] = min(temp, luma[17]);
    temp = luma[2]; luma[2] = min(luma[2], luma[16]); luma[16] = max(temp, luma[16]);
    temp = luma[22]; luma[22] = max(luma[22], luma[15]); luma[15] = min(temp, luma[15]);
    temp = luma[2]; luma[2] = min(luma[2], luma[14]); luma[14] = max(temp, luma[14]);
    temp = luma[22]; luma[22] = max(luma[22], luma[13]); luma[13] = min(temp, luma[13]);
    temp = luma[2]; luma[2] = min(luma[2], luma[12]); luma[12] = max(temp, luma[12]);
    temp = luma[22]; luma[22] = max(luma[22], luma[11]); luma[11] = min(temp, luma[11]);
    temp = luma[2]; luma[2] = min(luma[2], luma[10]); luma[10] = max(temp, luma[10]);
    temp = luma[22]; luma[22] = max(luma[22], luma[9]); luma[9] = min(temp, luma[9]);
    temp = luma[2]; luma[2] = min(luma[2], luma[8]); luma[8] = max(temp, luma[8]);
    temp = luma[22]; luma[22] = max(luma[22], luma[7]); luma[7] = min(temp, luma[7]);
    temp = luma[2]; luma[2] = min(luma[2], luma[6]); luma[6] = max(temp, luma[6]);
    temp = luma[22]; luma[22] = max(luma[22], luma[5]); luma[5] = min(temp, luma[5]);
    temp = luma[2]; luma[2] = min(luma[2], luma[4]); luma[4] = max(temp, luma[4]);
    temp = luma[22]; luma[22] = max(luma[22], luma[3]); luma[3] = min(temp, luma[3]);
    float nmax = (max(luma[22] + luma[23] * 2.0, c0_Y * 3.0) + luma[24]) / 4.0;
    float nmin = (min(luma[2]  + luma[1]  * 2.0, c0_Y * 3.0) + luma[0])  / 4.0;
    float min_dist  = min(abs(nmax - c0_Y), abs(c0_Y - nmin));
    float pos_scale = min_dist + min(L_overshoot, 1.0001 - min_dist - c0_Y);
    float neg_scale = min_dist + min(D_overshoot, 0.0001 + c0_Y - min_dist);
    pos_scale = min(pos_scale, scale_lim * (1.0 - scale_cs) + pos_scale * scale_cs);
    neg_scale = min(neg_scale, scale_lim * (1.0 - scale_cs) + neg_scale * scale_cs);
    float sp = max(sharpdiff, 0.0);
    float sn = min(sharpdiff, 0.0);
    sharpdiff = wpmean(sp, soft_lim(sp, pos_scale), cs.x)
              - wpmean(sn, soft_lim(sn, neg_scale), cs.y);
    float sharpdiff_lim = clamp(c0_Y + sharpdiff, 0.0, 1.0) - c0_Y;
    float satmul = (c0_Y + max(sharpdiff_lim * 0.9, sharpdiff_lim) * 1.03 + 0.03) / (c0_Y + 0.03);
    vec3 res = vec3(c0_Y) + (sharpdiff_lim * 3.0 + sharpdiff) / 4.0 + (c[0].rgb - vec3(c0_Y)) * satmul;
    res = clamp(res, 0.0, 1.0);
    fragColor = vec4(applyVibranceTail(res), 1.0);
}
