package com.davemorrissey.labs.subscaleview.decoder

import android.graphics.Bitmap

/** JNI surface of `libssiv_jpeg_decoder.so` (libjpeg-turbo, memory-mapped sources). */
internal object JpegNative {

	init {
		System.loadLibrary("ssiv_jpeg_decoder")
	}

	/** Maps `[offset, offset + length)` of [fd] read-only. The fd may be closed afterwards. 0 on failure. */
	@JvmStatic
	external fun nativeOpen(fd: Int, offset: Long, length: Long): Long

	/** `{width, height}` or null. */
	@JvmStatic
	external fun nativeInfo(handle: Long): IntArray?

	@JvmStatic
	external fun nativeClose(handle: Long)

	/**
	 * Decodes the region at 1/[sampleSize] (1, 2, 4 or 8). Null on any error: the caller falls back to Skia.
	 * The caller guarantees [handle] stays open for the duration of the call.
	 */
	@JvmStatic
	external fun nativeDecodeRegion(
		handle: Long,
		sampleSize: Int,
		left: Int,
		top: Int,
		right: Int,
		bottom: Int,
		rgb565: Boolean,
	): Bitmap?
}
