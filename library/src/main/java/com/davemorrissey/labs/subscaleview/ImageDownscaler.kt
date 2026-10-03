package com.davemorrissey.labs.subscaleview

/**
 * How SSIV resamples a decoded bitmap when it is drawn minified (zoomed out below 1:1, e.g. a
 * page wider than the screen). Independent of [ImageScaler], which only applies when magnified,
 * so any combination is valid (say [ImageScaler.ROBIDOUX] when zooming in and [DPID] when out).
 *
 * Non-default downscalers are only used when every drawn source pixel covers clearly less than
 * one screen pixel on both axes and the image is not rotated; otherwise [DEFAULT] is used.
 * Delivery is the same as for [ImageScaler]: live per frame with AGSL on Android 13+, and a
 * settled off-screen GLES 3.0 pass (~140 ms after the view stops moving) on older versions.
 */
public enum class ImageDownscaler {
	/** Android's canvas bilinear filtering (the historical SSIV behaviour). */
	DEFAULT,

	/**
	 * Detail-Preserving Image Downscaling (Weber et al., SIGGRAPH Asia 2016, lambda = 0.5): each
	 * output pixel is a weighted mean of its source neighbourhood in which pixels that differ most
	 * from the local average weigh most. Thin lines, text and screentone edges stay crisp where a
	 * plain box/bilinear average would wash them out. Costs more GPU time than [DEFAULT].
	 */
	DPID,
}
