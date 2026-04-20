package com.davemorrissey.labs.subscaleview.internal

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * A single tile in the tiled image grid.
 *
 * ### Thread safety
 *
 * [bitmap] is written from background decode coroutines and read from the main thread in
 * [onDraw]. The `@Volatile` annotation ensures the main thread always sees the latest
 * value written by a background thread.
 *
 * **Bitmap recycling happens exclusively on the main thread** (inside the generation-match
 * branch of [SubsamplingScaleImageView.loadTile]). We do NOT recycle the old bitmap from
 * the background thread because the main thread might be mid-draw using it. The sequence:
 *   1. Background: snapshot `oldBitmap = tile.bitmap`
 *   2. Background: `tile.bitmap = newBitmap; tile.isValid = true; tile.isLoading = false`
 *   3. Main: next draw frame picks up `newBitmap` via the volatile read
 *   4. Background: `oldBitmap?.recycle()` — safe because the main thread has already
 *      moved on to `newBitmap` for this tile
 *
 * Note: there is no explicit `setBitmap()` method here. The Kotlin compiler auto-generates
 * a `setBitmap()` JVM method for the `var bitmap` property, and adding a manually named
 * `fun setBitmap(...)` in the same class causes a **platform declaration clash** that
 * fails compilation. Use direct property assignment: `tile.bitmap = value`.
 */
internal class Tile {

    @JvmField
    var sampleSize: Int = 0

    /**
     * The decoded bitmap for this tile. Null if not yet loaded or after [recycle].
     * Written by background coroutines, read by the main thread's [onDraw].
     */
    @Volatile
    var bitmap: Bitmap? = null

    @JvmField
    var isLoading: Boolean = false

    @JvmField
    var isVisible: Boolean = false

    /**
     * True when [bitmap] was decoded in the current tile generation and is still valid.
     * Set to `false` by [SubsamplingScaleImageView.invalidateTiles] to force a reload at
     * the new downsampling level. Checked by [SubsamplingScaleImageView.refreshRequiredTiles].
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
     * Recycles this tile's bitmap and resets state. **Must be called from the main thread.**
     */
    fun recycle() {
        isVisible = false
        isLoading = false
        isValid = false
        bitmap?.recycle()
        bitmap = null
    }
}
