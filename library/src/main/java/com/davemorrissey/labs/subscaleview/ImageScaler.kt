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
     * three when zooming in; can show slight ringing on very high-contrast edges.
     */
    CATMULL_ROM,

    /**
     * Cubic B-Spline bicubic (4 bilinear taps, all weights positive). Smooth, no ringing, but
     * softer than Catmull-Rom.
     */
    BSPLINE,
    ;
}
