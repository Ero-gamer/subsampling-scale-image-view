package com.davemorrissey.labs.subscaleview.internal

/**
 * Emits the shader source for every bicubic (Mitchell-Netravali BC-spline) zoom scaler from ONE
 * parametrized kernel body, in both dialects SSIV needs:
 * - GLSL ES 3.00 (Android 9-12, [BicubicRenderer])
 * - AGSL (Android 13+, [BitmapScaler]'s `RuntimeShader`)
 *
 * A BC-cubic kernel is entirely determined by two numbers, B and C (Mitchell & Netravali,
 * *Reconstruction Filters in Computer Graphics*, 1988); different (B, C) pairs are different
 * *presets* of the same kernel, not different code. [CATMULL_ROM_B]/[CATMULL_ROM_C],
 * [ROBIDOUX_B]/[ROBIDOUX_C] and [ROBIDOUX_SHARP_B]/[ROBIDOUX_SHARP_C] are the exact (B, C) values
 * ImageMagick's `resize.c` uses for the filters of the same names (`RobidouxFilter` /
 * `RobidouxSharpFilter` entries in its `filters[]` table) — this is the authoritative source
 * these constants were copied from.
 *
 * ### Why one generator instead of four shader strings
 * Every BC-cubic reduces to the same 4-tap-per-axis structure: the outer two taps (signed,
 * responsible for ringing/sharpness) fetched directly, the inner two taps (always positive and
 * never close to summing to zero, for every (B, C) pair used here — verified numerically, see
 * the project history) folded into one hardware-bilinear fetch per axis, exactly like the
 * original hand-written Catmull-Rom kernel. So the *only* difference between all three scalers is
 * the weight polynomial's B, C coefficients — everything else (the fold algebra, the sigmoid
 * companding, the anti-ring clamp) is identical code. Making B, C a shader parameter rather than
 * hardcoding three near-duplicate kernel bodies means adding another BC-cubic later (if ever
 * wanted) is a one-line preset addition here, not a new shader to re-verify from scratch.
 *
 * ### The two dialects are NOT the same language
 * GLSL ES 3.00 and AGSL look superficially alike but differ in exactly the ways this generator
 * has to paper over: GLSL spells its vector/scalar types `vec2`/`vec4`/`float`; AGSL (like the
 * HLSL/SkSL it's derived from) spells them `float2`/`float4`/`float`, with an additional `half`/
 * `half2`/`half4` family for reduced precision. Neither accepts the other's type spelling. Every
 * template method below therefore takes the vector/scalar type NAMES as parameters ([Types])
 * rather than hardcoding either dialect's spelling, so the exact same weight/fold/sigmoid/
 * anti-ring algebra is never duplicated, only re-spelled.
 *
 * Sigmoidal companding (libplacebo defaults center=0.75, slope=6.5) and the true 4-neighbour
 * anti-ring clamp (Qualcomm `VK_QCOM_filter_cubic_clamp` / US 12,254,555 B2 technique) are always
 * applied — see [sigmoidAndAntiringSource] for the derivation and round-trip verification notes.
 */
internal object ScalerShaderSource {
	/** (B, C) for the exact Catmull-Rom spline (interpolating; sharp, some ringing). */
	const val CATMULL_ROM_B = 0.0
	const val CATMULL_ROM_C = 0.5

	/**
	 * (B, C) = `((228 - 108*sqrt(2))/199, ...)`, copied verbatim from ImageMagick's
	 * `MagickCore/resize.c` `filters[]` table entry for `RobidouxFilter`: a Keys cubic tuned so
	 * a pure translation ("no-op" resample) exactly preserves horizontal/vertical lines — a
	 * property plain Catmull-Rom lacks. Close to both Mitchell and a sharpened Lanczos2.
	 */
	const val ROBIDOUX_B = 0.37821575509399867
	const val ROBIDOUX_C = 0.31089212245300067

	/**
	 * Copied verbatim from ImageMagick's `RobidouxSharpFilter` table entry: a sharper variant of
	 * [ROBIDOUX_B]/[ROBIDOUX_C], tuned to minimise the maximum possible change to a pixel value
	 * already at an extreme (0 or 1) under a no-op resample.
	 */
	const val ROBIDOUX_SHARP_B = 0.2620145123990142
	const val ROBIDOUX_SHARP_C = 0.3689927438004929

	/** Which dialect to spell types for. */
	private class Types(
		val vec2: String,
		val vec4: String,
		val scalar: String,
		/** Full-precision scalar/vector names, for coordinates and accumulators (AGSL `half` is too coarse). */
		val fvec2: String,
		val fvec4: String,
	)

	private val GLSL = Types(vec2 = "vec2", vec4 = "vec4", scalar = "float", fvec2 = "vec2", fvec4 = "vec4")
	private val AGSL = Types(vec2 = "float2", vec4 = "half4", scalar = "half", fvec2 = "float2", fvec4 = "float4")

	/** Neighbourhood taps per axis above which [dpidSource] samples with a stride (see there). */
	private const val DPID_MAX_TAPS = 16

	/**
	 * DPID — Detail-Preserving Image Downscaling (Weber, Waechter, Amend, Guthe, Goesele, SIGGRAPH
	 * Asia 2016): `O[p] = sum_q I[q] * ||I[q] - G[p]||^lambda / sum_q ||I[q] - G[p]||^lambda`, where
	 * the guidance `G[p]` is the plain box average of the SAME neighbourhood `q`, so one shader
	 * invocation computes both (two loops over one neighbourhood, no pre-pass). Pixels that differ
	 * most from the local average (fine detail, line art) get the most weight, so downscaled
	 * lines stay crisp instead of being averaged away. Chosen over the SSIM-optimising downscaler
	 * of Oztireli & Gross because that one is covered by an active patent (WO2017017584A1).
	 *
	 * `lambda` = 0.5 (the paper's own user study favoured 0.5-1.0), which makes the weight
	 * `sqrt(||d||)` = `(||d||^2)^0.25`: two square roots, no `pow`.
	 *
	 * Neighbourhood of a destination pixel centred on source position `samplePos`: source texels
	 * `[floor(samplePos - invScale/2), floor(samplePos + invScale/2))`, at least one texel, clamped
	 * to the bitmap. Consecutive destination pixels therefore partition the source exactly. At a
	 * tile border the box is truncated to its own tile (tiles are separate textures), a
	 * sub-source-pixel inaccuracy on the border row only. Boxes wider than [DPID_MAX_TAPS] texels
	 * per axis are sampled with a stride (every Nth texel) — graceful degradation for an extreme
	 * zoom-out, bounded cost (<= 16x16 taps per pass). Loops have constant bounds and no integer
	 * division, which both GLSL ES 3.00 and AGSL (SkSL) require.
	 */
	private fun dpidSource(
		t: Types,
		fetch: (String) -> String,
	): String {
		val f2 = t.fvec2
		val f4 = t.fvec4
		val v4 = t.vec4
		return """
const int DPID_TAPS = $DPID_MAX_TAPS;
$v4 dpid($f2 samplePos, $f2 boxScale, $f2 imgSize) {
    $f2 halfBox = 0.5 * boxScale;
    $f2 lo = clamp(floor(samplePos - halfBox), $f2(0.0), imgSize - 1.0);
    $f2 hi = min(max(floor(samplePos + halfBox), lo + 1.0), imgSize);
    $f2 stride = max($f2(1.0), ceil((hi - lo) / float(DPID_TAPS)));

    $f4 guide = $f4(0.0);
    float n = 0.0;
    for (int j = 0; j < DPID_TAPS; j++) {
        float y = lo.y + float(j) * stride.y;
        if (y >= hi.y) break;
        for (int i = 0; i < DPID_TAPS; i++) {
            float x = lo.x + float(i) * stride.x;
            if (x >= hi.x) break;
            guide += $f4(${fetch("$f2(x + 0.5, y + 0.5)")});
            n += 1.0;
        }
    }
    guide /= n;

    $f4 acc = $f4(0.0);
    float k = 0.0;
    for (int j = 0; j < DPID_TAPS; j++) {
        float y = lo.y + float(j) * stride.y;
        if (y >= hi.y) break;
        for (int i = 0; i < DPID_TAPS; i++) {
            float x = lo.x + float(i) * stride.x;
            if (x >= hi.x) break;
            $f4 c = $f4(${fetch("$f2(x + 0.5, y + 0.5)")});
            $f4 d = c - guide;
            float w = sqrt(sqrt(max(dot(d.rgb, d.rgb), 1e-8)));
            acc += c * w;
            k += w;
        }
    }
    return $v4(acc / k);
}
"""
	}

	/**
	 * Sigmoid companding (libplacebo defaults center=0.75, slope=6.5) resamples in "logit" space
	 * so overshoot lands closer to the true 0/1 asymptotes once undone, rather than the raw
	 * linear overshoot a naive clamp(0,1) has to clip. Constants below are precomputed from
	 * those two: offset = 1/(1+exp(slope*center)), scale = 1/(1+exp(slope*(center-1))) - offset.
	 * Verified: unsigmoidize(sigmoidize(x)) == x to double-precision float error for all x in
	 * [0,1]. The true 4-neighbour anti-ring clamp (Qualcomm VK_QCOM_filter_cubic_clamp /
	 * US 12,254,555 B2) blends the resampled colour toward being clamped to the min/max of the 4
	 * texel centres immediately surrounding the sample point — not the further-out kernel taps,
	 * which is what would actually cause ringing if left unclamped. 0.8 matches libplacebo/mpv's
	 * common "antiring" default. Shared verbatim by every preset since it doesn't depend on B,C.
	 */
	private fun sigmoidAndAntiringSource(t: Types): String =
		"""
const float SIG_CENTER = 0.75;
const float SIG_SLOPE = 6.5;
const float SIG_OFFSET = 0.00757724;
const float SIG_SCALE = 0.82790630;

${t.vec4} sigmoidize(${t.vec4} c) {
    ${t.vec4} x = clamp(c, ${t.vec4}(0.0), ${t.vec4}(1.0));
    return ${t.vec4}(SIG_CENTER) - ${t.vec4}(1.0 / SIG_SLOPE) * log(${t.vec4}(1.0) / (x * ${t.vec4}(SIG_SCALE) + ${t.vec4}(SIG_OFFSET)) - ${t.vec4}(1.0));
}
${t.vec4} unsigmoidize(${t.vec4} y) {
    return (${t.vec4}(1.0) / (${t.vec4}(1.0) + exp(${t.vec4}(SIG_SLOPE) * (${t.vec4}(SIG_CENTER) - y))) - ${t.vec4}(SIG_OFFSET)) / ${t.vec4}(SIG_SCALE);
}
const float ANTIRING_STRENGTH = 0.8;
${t.vec4} antiring(${t.vec4} result, ${t.vec4} c00, ${t.vec4} c10, ${t.vec4} c01, ${t.vec4} c11) {
    ${t.vec4} lo = min(min(c00, c10), min(c01, c11));
    ${t.vec4} hi = max(max(c00, c10), max(c01, c11));
    return mix(result, clamp(result, lo, hi), ${t.scalar}(ANTIRING_STRENGTH));
}
"""

	/**
	 * The kernel body shared by both dialects, as a function of (B, C), emitted with [t]'s type
	 * spellings and [fetch] as the dialect's texture-sampling call (`image.eval(...)` for AGSL, a
	 * local `fetch(...)` wrapper over `texture()` for GLSL — see [glslFragmentBody] /
	 * [agslShader]).
	 *
	 * Weight polynomials are the general 4-tap Mitchell-Netravali BC-cubic (Mitchell & Netravali
	 * 1988, as tabulated by ImageMagick's `CubicBC` filter function): for a sample at fractional
	 * position `f` within its texel cell, the taps at relative offsets -1, 0, +1, +2 are
	 * `Q(1+f)`, `P(f)`, `P(1-f)`, `Q(2-f)` where `P(x) = P0 + P2*x^2 + P3*x^3` (0<=x<1) and
	 * `Q(x) = Q0 + Q1*x + Q2*x^2 + Q3*x^3` (1<=x<2), with P0..Q3 the standard closed forms in B,C.
	 * Verified against a direct (unfolded) 4-tap evaluation and cross-checked to reduce to the
	 * original hand-derived Catmull-Rom closed form at (B=0, C=0.5), both to float precision.
	 */
	private fun kernelSource(
		t: Types,
		fetch: (String) -> String,
	): String {
		val v2 = t.vec2
		val v4 = t.vec4
		val s = t.scalar
		return """
$v4 bcCubic($v2 samplePos, $s B, $s C) {
    $v2 texPos1 = floor(samplePos - 0.5) + 0.5;
    $v2 f = samplePos - texPos1;
    $v2 f2 = f * f;
    $v2 f3 = f2 * f;

    $s P0 = (6.0 - 2.0*B) / 6.0;
    $s P2 = (-18.0 + 12.0*B + 6.0*C) / 6.0;
    $s P3 = (12.0 - 9.0*B - 6.0*C) / 6.0;
    $s Q0 = (8.0*B + 24.0*C) / 6.0;
    $s Q1 = (-12.0*B - 48.0*C) / 6.0;
    $s Q2 = (6.0*B + 30.0*C) / 6.0;
    $s Q3 = (-B - 6.0*C) / 6.0;

    // w0 = Q(1+f) [offset -1], w1 = P(f) [offset 0], w2 = P(1-f) [offset 1], w3 = Q(2-f) [offset 2]
    $v2 onePlusF = 1.0 + f;
    $v2 w0 = Q0 + Q1*onePlusF + Q2*onePlusF*onePlusF + Q3*onePlusF*onePlusF*onePlusF;
    $v2 w1 = P0 + P2*f2 + P3*f3;
    $v2 oneMinusF = 1.0 - f;
    $v2 w2 = P0 + P2*oneMinusF*oneMinusF + P3*oneMinusF*oneMinusF*oneMinusF;
    $v2 twoMinusF = 2.0 - f;
    $v2 w3 = Q0 + Q1*twoMinusF + Q2*twoMinusF*twoMinusF + Q3*twoMinusF*twoMinusF*twoMinusF;

    // Inner pair (w1, w2) is always positive and never near-zero-sum for every (B,C) preset
    // this generator ships (verified numerically) — folding it into one hardware-bilinear
    // fetch per axis is safe. The outer pair (w0, w3) can be negative (that is what gives a
    // BC-cubic its sharpness) and is fetched directly, one texel each.
    $v2 w12 = w1 + w2;
    $v2 p0 = texPos1 - 1.0;
    $v2 p3 = texPos1 + 2.0;
    $v2 p12 = texPos1 + w2 / w12;

    // Direct nearest-neighbour texel centres (texPos1 and texPos1+1 are both exact texel
    // centres: floor(samplePos-0.5)+0.5 is always of the form integer+0.5) — raw, for the
    // anti-ring reference only, not part of the weighted sum.
    $v4 c00 = ${fetch("$v2(texPos1.x,     texPos1.y)")};
    $v4 c10 = ${fetch("$v2(texPos1.x+1.0, texPos1.y)")};
    $v4 c01 = ${fetch("$v2(texPos1.x,     texPos1.y+1.0)")};
    $v4 c11 = ${fetch("$v2(texPos1.x+1.0, texPos1.y+1.0)")};

    $v4 r = $v4(0.0);
    r += sigmoidize(${fetch("$v2(p0.x,  p0.y )")}) * $s(w0.x  * w0.y );
    r += sigmoidize(${fetch("$v2(p12.x, p0.y )")}) * $s(w12.x * w0.y );
    r += sigmoidize(${fetch("$v2(p3.x,  p0.y )")}) * $s(w3.x  * w0.y );
    r += sigmoidize(${fetch("$v2(p0.x,  p12.y)")}) * $s(w0.x  * w12.y);
    r += sigmoidize(${fetch("$v2(p12.x, p12.y)")}) * $s(w12.x * w12.y);
    r += sigmoidize(${fetch("$v2(p3.x,  p12.y)")}) * $s(w3.x  * w12.y);
    r += sigmoidize(${fetch("$v2(p0.x,  p3.y )")}) * $s(w0.x  * w3.y );
    r += sigmoidize(${fetch("$v2(p12.x, p3.y )")}) * $s(w12.x * w3.y );
    r += sigmoidize(${fetch("$v2(p3.x,  p3.y )")}) * $s(w3.x  * w3.y );

    r = unsigmoidize(r);
    return antiring(r, c00, c10, c01, c11);
}
"""
	}

	/**
	 * GLSL ES 3.00 fragment shader body for [BicubicRenderer]: `u_mode` selects the (B, C) preset
	 * (0=Catmull-Rom, 1=Robidoux, 2=RobidouxSharp, 3=DPID downscale — see
	 * [BicubicRenderer]'s doc for the mapping), so one compiled program serves every scaler
	 * without a re-link. Assumes the caller's shader already declares `uniform sampler2D u_texture;`,
	 * `uniform vec2 u_texSize;`, `uniform vec2 u_invScale;`, and a `vec4 fetch(vec2 pixelPos)`
	 * wrapper over `texture(u_texture, pixelPos / u_texSize)` (all present in [BicubicRenderer]).
	 */
	fun glslFragmentBody(): String =
		sigmoidAndAntiringSource(GLSL) +
			kernelSource(GLSL, fetch = { p -> "fetch($p)" }) +
			dpidSource(GLSL, fetch = { p -> "fetch($p)" }) + """
uniform int u_mode;
vec4 resample(vec2 samplePos) {
    if (u_mode == 3) return dpid(samplePos, u_invScale, u_texSize);
    if (u_mode == 0) return bcCubic(samplePos, float($CATMULL_ROM_B), float($CATMULL_ROM_C));
    if (u_mode == 1) return bcCubic(samplePos, float($ROBIDOUX_B), float($ROBIDOUX_C));
    return bcCubic(samplePos, float($ROBIDOUX_SHARP_B), float($ROBIDOUX_SHARP_C));
}
"""

	/**
	 * AGSL shader source for [BitmapScaler]'s `RuntimeShader`, one per (B, C) preset (AGSL has no
	 * notion of a mode uniform selecting between hand-written kernel bodies the way the GLSL path
	 * does — each preset is its own tiny `RuntimeShader` program, all sharing this one generator).
	 */
	fun agslShader(
		b: Double,
		c: Double,
	): String =
		"""
uniform shader image;
uniform float2 dstOrigin;
uniform float2 invScale;
""" + sigmoidAndAntiringSource(AGSL) +
			kernelSource(AGSL, fetch = { p -> "half4(image.eval($p))" }) + """

half4 main(float2 fragCoord) {
    float2 samplePos = (fragCoord - dstOrigin) * invScale;
    half4 r = bcCubic(samplePos, half($b), half($c));
    // Negative outer-tap lobes (and the sigmoid round-trip's own float error) can still
    // overshoot very slightly; keep the result a valid premultiplied colour.
    float4 rf = clamp(float4(r), 0.0, 1.0);
    rf.rgb = min(rf.rgb, rf.aaa);
    return half4(rf);
}
"""

	/**
	 * AGSL DPID downscaler for [BitmapScaler] (see [dpidSource]). Needs the `texSize` uniform
	 * (the bitmap size) for clamping the neighbourhood, which AGSL cannot query from a shader.
	 */
	fun agslDpidShader(): String =
		"""
uniform shader image;
uniform float2 dstOrigin;
uniform float2 invScale;
uniform float2 texSize;
""" + dpidSource(AGSL, fetch = { p -> "half4(image.eval($p))" }) + """

half4 main(float2 fragCoord) {
    float2 samplePos = (fragCoord - dstOrigin) * invScale;
    float4 rf = clamp(float4(dpid(samplePos, invScale, texSize)), 0.0, 1.0);
    rf.rgb = min(rf.rgb, rf.aaa);
    return half4(rf);
}
"""
}
