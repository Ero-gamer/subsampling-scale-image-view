package com.davemorrissey.labs.subscaleview.internal

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Represents a single tile within the tiled image decoding grid.
 *
 * ### Thread safety
 *
 * [bitmap] is written from background coroutines (in [SubsamplingScaleImageView.loadTile])
 * and read from the main thread (in [SubsamplingScaleImageView.onDraw]). This is the same
 * pattern as the original SSIV — writes are effectively serialized because
 * [SubsamplingScaleImageView.loadTile] only writes when the tile generation matches (i.e.,
 * only one coroutine per tile can "win" the generation check), and reads happen on the
 * main thread's draw frame.
 *
 * We do NOT recycle the old bitmap inside the bitmap property setter (the background coroutine call site).
 * Recycling a bitmap while the main thread may be in the middle of drawing it causes
 * `RuntimeException: Canvas: trying to use a recycled bitmap`. Instead, the old bitmap is
 * recycled only from [recycle], which is always called from the main thread.
 */
internal class Tile {

    @JvmField
    var sampleSize: Int = 0

    /** The decoded bitmap for this tile. May be null if not yet loaded or recycled. */
    @Volatile
    var bitmap: Bitmap? = null

    @JvmField
    var isLoading: Boolean = false

    @JvmField
    var isVisible: Boolean = false

    /**
     * True if [bitmap] was decoded during the CURRENT tile generation and is still valid.
     * Set to false by [SubsamplingScaleImageView.invalidateTiles] when downsampling changes,
     * causing a fresh reload. Controls whether [SubsamplingScaleImageView.refreshRequiredTiles]
     * re-queues a decode for this tile.
     */
    @JvmField
    var isValid: Boolean = false

    @JvmField
    var sRect: Rect = Rect()

    @JvmField
    val vRect: Rect = Rect()

    @JvmField
    val fileSRect: Rect = Rect()

    /**
     * Recycles this tile's bitmap and resets state. Must be called from the main thread.
     */
    fun recycle() {
        isVisible = false
        isLoading = false
        isValid = false
        bitmap?.recycle()
        bitmap = null
    }
}
