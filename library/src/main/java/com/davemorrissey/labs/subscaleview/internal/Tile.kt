package com.davemorrissey.labs.subscaleview.internal

import android.graphics.Bitmap
import android.graphics.Rect

internal class Tile {
	@JvmField
	var sampleSize = 0

	// BUG 2 FIX: The original setter auto-recycled the old bitmap inline:
	//   field?.recycle()
	//   field = value
	//   isValid = true
	// This was NOT thread-safe — if a background loadTile coroutine assigned a
	// low-res bitmap after the main thread had already assigned the high-res one,
	// the high-res bitmap was silently recycled and replaced with the blurry one.
	//
	// The fix: loadTile() now calls setBitmapSafe() which is guarded by a
	// generation check in SubsamplingScaleImageView before calling it.
	// The property setter is only used from recycle() (null assignment, main thread).
	var bitmap: Bitmap? = null

	@JvmField
	var isLoading = false

	@JvmField
	var isVisible = false

	@JvmField
	var isValid = false

	// Volatile fields instantiated once then updated before use to reduce GC.
	@JvmField
	var sRect: Rect = Rect()

	@JvmField
	val vRect = Rect()

	@JvmField
	val fileSRect = Rect()

	/**
	 * Atomically replaces the tile bitmap, recycling the old one.
	 * Only called from SubsamplingScaleImageView.loadTile() after the generation
	 * check confirms the decoded bitmap is still valid for this tile.
	 */
	fun storeBitmap(newBitmap: Bitmap?) {
		val old = bitmap
		bitmap = newBitmap
		isValid = true
		old?.recycle()
	}

	fun recycle() {
		isVisible = false
		val old = bitmap
		bitmap = null
		isValid = false
		old?.recycle()
	}
}
