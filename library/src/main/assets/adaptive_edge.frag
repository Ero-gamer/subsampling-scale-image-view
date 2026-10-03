#version 300 es
// Adaptive-Sharpen, pass one (edge detection). Port of bacondither/Adaptive-sharpen, version
// 2018-04-14, "Adaptive-sharpen - Pass one.hlsl" (BSD-2-Clause, Copyright (c) 2015-2018,
// bacondither; licence text is reproduced in adaptive_sharpen.frag).
//
// Output: rgb = the input colour, a = edge strength * contrast compression. It is written to an
// RGBA16F target (edge strengths exceed 1.0). The original adds a_offset = 2.0 to the edge value;
// this port stores it without the offset and adaptive_sharpen.frag is written for that.
precision highp float;
precision highp int;

in highp vec2 v_texCoord;
out vec4 fragColor;

uniform highp sampler2D u_texture;
uniform highp vec2 u_texelSize;

vec3 get(float x, float y) {
    return clamp(texture(u_texture, v_texCoord + u_texelSize * vec2(x, y)).rgb, 0.0, 1.0);
}

void main() {
    // [                c9                ]
    // [           c1,  c2,  c3           ]
    // [      c10, c4,  c0,  c5, c11      ]
    // [           c6,  c7,  c8           ]
    // [                c12               ]
    vec3 c0  = get( 0.0,  0.0);
    vec3 c1  = get(-1.0, -1.0);
    vec3 c2  = get( 0.0, -1.0);
    vec3 c3  = get( 1.0, -1.0);
    vec3 c4  = get(-1.0,  0.0);
    vec3 c5  = get( 1.0,  0.0);
    vec3 c6  = get(-1.0,  1.0);
    vec3 c7  = get( 0.0,  1.0);
    vec3 c8  = get( 1.0,  1.0);
    vec3 c9  = get( 0.0, -2.0);
    vec3 c10 = get(-2.0,  0.0);
    vec3 c11 = get( 2.0,  0.0);
    vec3 c12 = get( 0.0,  2.0);

    // Blur, gauss 3x3
    vec3 blur = (2.0 * (c2 + c4 + c5 + c7) + (c1 + c3 + c6 + c8) + 4.0 * c0) / 16.0;

    // Contrast compression, center = 0.5, scaled to 1/3
    float c_comp = clamp(4.0 / 15.0 + 0.9 * exp2(dot(blur, vec3(-37.0 / 15.0))), 0.0, 1.0);

    // Edge detection. Relative matrix weights:
    // [          1          ]
    // [      4,  5,  4      ]
    // [  1,  5,  6,  5,  1  ]
    // [      4,  5,  4      ]
    // [          1          ]
    vec3 e = 1.38 * abs(blur - c0)
           + 1.15 * (abs(blur - c2) + abs(blur - c4) + abs(blur - c5) + abs(blur - c7))
           + 0.92 * (abs(blur - c1) + abs(blur - c3) + abs(blur - c6) + abs(blur - c8))
           + 0.23 * (abs(blur - c9) + abs(blur - c10) + abs(blur - c11) + abs(blur - c12));
    float edge = length(e);

    fragColor = vec4(texture(u_texture, v_texCoord).rgb, edge * c_comp);
}
