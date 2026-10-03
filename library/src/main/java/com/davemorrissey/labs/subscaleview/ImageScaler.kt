package com.davemorrissey.labs.subscaleview

/**
 * How SSIV resamples a decoded bitmap when it is drawn magnified (zoomed in past 1:1).
 *
 * Non-default scalers are only used when every source pixel covers clearly more than one screen
 * pixel and the image is not rotated. At or below 1:1 (zoomed out) every scaler behaves as
 * [DEFAULT], because a bicubic kernel without a pre-filter aliases when minifying.
 *
 * Two implementations, chosen automatically:
 * - **Android 13+** (`RuntimeShader`/AGSL, hardware canvas): resampled live on every frame, so
 *   it follows pinch-zoom and panning continuously.
 * - **Android 12 and older** (off-screen GLES 3.0): while you pinch/pan/fling the normal bilinear
 *   path is shown; ~140 ms after the view stops, the visible area is re-rendered once with the
 *   selected kernel and swapped in. Needs OpenGL ES 3.0; without it the scaler silently stays on
 *   [DEFAULT].
 */
public enum class ImageScaler {
	/** Android's canvas bilinear filtering (the historical SSIV behaviour). */
	DEFAULT,

	/**
	 * Exact Catmull-Rom bicubic (9 bilinear taps, negative outer lobes kept). Sharpest of the
	 * bicubic scalers when zooming in; can show slight ringing on very high-contrast edges.
	 */
	CATMULL_ROM,

	/**
	 * Keys cubic (B, C) = (0.37821575509399867, 0.31089212245300067) — ImageMagick's
	 * `RobidouxFilter` constants (see [com.davemorrissey.labs.subscaleview.internal.ScalerShaderSource]).
	 * Tuned so a pure translation exactly preserves horizontal/vertical lines, a property plain
	 * Catmull-Rom lacks; a middle ground between Catmull-Rom's sharpness and plain bilinear softness.
	 */
	ROBIDOUX,

	/**
	 * A sharper variant of [ROBIDOUX], (B, C) = (0.2620145123990142, 0.3689927438004929) —
	 * ImageMagick's `RobidouxSharpFilter` constants. Tuned to minimise the maximum change to a
	 * pixel value already at an extreme (0 or 1) under a no-op resample.
	 */
	ROBIDOUX_SHARP,
}
