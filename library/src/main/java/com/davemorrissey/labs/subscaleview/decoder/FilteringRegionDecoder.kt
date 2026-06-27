package com.davemorrissey.labs.subscaleview.decoder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import androidx.annotation.WorkerThread
import com.davemorrissey.labs.subscaleview.ImageSource

/**
 * [ImageRegionDecoder] wrapper that applies CPU sharpening and/or vibrance to every
 * decoded tile immediately after the inner decoder returns it.
 *
 * This eliminates the previous encode-to-disk approach (where the full filtered page
 * was saved as PNG to [ProcessedPageCache] and then re-tiled by SSIV), replacing it with
 * a pure in-memory per-tile pass that:
 *   - never writes any extra file to disk
 *   - never decodes the whole image at once
 *   - works identically on 256×512px tiles as on full pages
 *   - is compatible with [LiJpegTurboRegionDecoder] as the inner decoder (JPEG tiles)
 *     and [SkiaImageRegionDecoder] / [SkiaPooledImageRegionDecoder] for all other formats
 *
 * Thread safety: [decodeRegion] may be called from multiple threads concurrently by SSIV's
 * tile-loading executor. Each call operates on its own tile [Bitmap] — no shared mutable
 * state exists between calls, so no locking is required here. The inner decoder is
 * responsible for its own locking (LiJpegTurboRegionDecoder uses a ReadWriteLock).
 *
 * The filter math is identical to [org.koitharu.kotatsu.core.ui.image.ImageFiltersTransformation]
 * (which is kept for the ColorFilterConfigActivity preview and any non-SSIV callers).
 */
public class FilteringRegionDecoder(
    private val inner: ImageRegionDecoder,
    private val sharpening: Float,
    private val vibrance: Float,
) : ImageRegionDecoder {

    override fun init(context: Context, uri: Uri): Point = inner.init(context, uri)

    override fun init(context: Context, source: ImageSource): Point = inner.init(context, source)

    @WorkerThread
    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        val tile = inner.decodeRegion(sRect, sampleSize)
        return applyFilters(tile)
    }

    override val isReady: Boolean
        get() = inner.isReady

    override fun recycle() = inner.recycle()

    // ── filter math ──────────────────────────────────────────────────────────

    private fun applyFilters(tile: Bitmap): Bitmap {
        val doSharpen = sharpening > 0.01f
        val doVibrance = vibrance != 0f
        if (!doSharpen && !doVibrance) return tile

        val w = tile.width
        val h = tile.height
        if (w <= 0 || h <= 0) return tile

        val working = if (tile.config != Bitmap.Config.ARGB_8888 || !tile.isMutable) {
            tile.copy(Bitmap.Config.ARGB_8888, true).also { tile.recycle() }
        } else {
            tile
        }

        val pixelCount = w * h
        val src = srcPool.acquire(pixelCount)
        working.getPixels(src, 0, w, 0, 0, w, h)

        val k = if (doSharpen) kernelStrength(sharpening) else 0f
        // Sharpening reads src while writing to out to avoid corrupting neighbour pixels mid-pass.
        val out = if (doSharpen) outPool.acquire(pixelCount) else src

        for (y in 0 until h) {
            val rowStart = y * w
            val hasAbove = y > 0
            val hasBelow = y < h - 1
            for (x in 0 until w) {
                val idx = rowStart + x
                val px = src[idx]
                var r: Int
                var g: Int
                var b: Int

                if (doSharpen && x > 0 && x < w - 1 && hasAbove && hasBelow) {
                    val top    = src[idx - w]
                    val bottom = src[idx + w]
                    val left   = src[idx - 1]
                    val right  = src[idx + 1]
                    r = sharpenChannel((px shr 16) and 0xFF, (top shr 16) and 0xFF,
                        (bottom shr 16) and 0xFF, (left shr 16) and 0xFF, (right shr 16) and 0xFF, k)
                    g = sharpenChannel((px shr 8) and 0xFF, (top shr 8) and 0xFF,
                        (bottom shr 8) and 0xFF, (left shr 8) and 0xFF, (right shr 8) and 0xFF, k)
                    b = sharpenChannel(px and 0xFF, top and 0xFF,
                        bottom and 0xFF, left and 0xFF, right and 0xFF, k)
                } else {
                    r = (px shr 16) and 0xFF
                    g = (px shr 8) and 0xFF
                    b = px and 0xFF
                }

                if (doVibrance) {
                    val factor = vibranceFactor(r, g, b, vibrance)
                    if (factor != 1f) {
                        val mean = (r + g + b) / 3f
                        r = clamp255(mean + (r - mean) * factor)
                        g = clamp255(mean + (g - mean) * factor)
                        b = clamp255(mean + (b - mean) * factor)
                    }
                }

                out[idx] = (px and ALPHA_MASK) or (r shl 16) or (g shl 8) or b
            }
        }

        working.setPixels(out, 0, w, 0, 0, w, h)
        return working
    }

    // ── sharpening ───────────────────────────────────────────────────────────

    /** Maps the 0..1 slider to Laplacian kernel strength k (0..0.5). */
    private fun kernelStrength(amount: Float): Float =
        amount.coerceIn(0f, 1f) * SHARPEN_SCALAR

    /** Standard 5-point discrete Laplacian sharpen for one channel (0..255). */
    private fun sharpenChannel(
        center: Int, top: Int, bottom: Int, left: Int, right: Int, k: Float,
    ): Int {
        val v = center + k * (4 * center - top - bottom - left - right)
        return when {
            v <= 0f   -> 0
            v >= 255f -> 255
            else      -> (v + 0.5f).toInt()
        }
    }

    // ── vibrance ─────────────────────────────────────────────────────────────

    /**
     * Selective vibrance factor for a pixel with channels [r]/[g]/[b] (0..255).
     * Returns a multiplier to apply to (channel - mean); 1f = no change.
     * Dull/muted pixels (low HSL saturation) receive the most boost.
     */
    private fun vibranceFactor(r: Int, g: Int, b: Int, v: Float): Float {
        if (v == 0f) return 1f
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val delta = maxC - minC
        if (delta == 0) return 1f

        val lSum = maxC + minC
        val denom = (if (lSum <= 255) lSum else 510 - lSum).coerceAtLeast(1)
        val sat = delta.toFloat() / denom
        val selectivity = (1f - sat) * (1f - sat)
        return 1f + v.coerceIn(-1f, 1f) * selectivity * VIBRANCE_SCALAR
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun clamp255(v: Float): Int = when {
        v <= 0f   -> 0
        v >= 255f -> 255
        else      -> (v + 0.5f).toInt()
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    /**
     * Produces [FilteringRegionDecoder] instances wrapping decoders from [innerFactory].
     *
     * [bitmapConfig] is forwarded from [innerFactory] so that [ReaderSettings.applyBitmapConfig]
     * can compare configs correctly via [DecoderFactory.bitmapConfig].
     *
     * [sharpening] and [vibrance] are stored on the factory so that a change in filter
     * params is detected in [ReaderSettings.applyBitmapConfig] by comparing factory instances,
     * which triggers [SubsamplingScaleImageView.setImage] with the new factory installed.
     */
    public class Factory(
        private val innerFactory: DecoderFactory<out ImageRegionDecoder>,
        val sharpening: Float,
        val vibrance: Float,
    ) : DecoderFactory<FilteringRegionDecoder> {

        override val bitmapConfig: Bitmap.Config?
            get() = innerFactory.bitmapConfig

        override fun make(): FilteringRegionDecoder =
            FilteringRegionDecoder(innerFactory.make(), sharpening, vibrance)
    }

    // ── pixel buffer pool ─────────────────────────────────────────────────────

    /**
     * Per-instance thread-local pools for the src and out pixel arrays.
     *
     * These are intentionally on the instance, not the companion object. If they were
     * shared across all [FilteringRegionDecoder] instances (companion-level), a
     * [reloadImage] call that creates a new decoder while the old one still has in-flight
     * tile coroutines would cause both decoders to share the same thread-local arrays on
     * the same threads — old-decoder writes to out[] would corrupt new-decoder reads from
     * src[], producing partially blurred / wrong-colour tile regions.
     *
     * Instance-level ThreadLocals mean each decoder has its own set of arrays per thread.
     * The tradeoff is one extra IntArray allocation per thread on first use after reload,
     * which is negligible compared to the tile bitmap itself.
     */
    private val srcPool = ThreadLocal<IntArray>()
    private val outPool = ThreadLocal<IntArray>()

    private fun ThreadLocal<IntArray>.acquire(minSize: Int): IntArray {
        val existing = get()
        if (existing != null && existing.size >= minSize) return existing
        var cap = minSize.coerceAtLeast(1024)
        cap = Integer.highestOneBit(cap - 1) shl 1
        val arr = IntArray(cap)
        set(arr)
        return arr
    }

    private companion object {
        private const val SHARPEN_SCALAR  = 0.5f
        private const val VIBRANCE_SCALAR = 1.5f
        private val ALPHA_MASK = 0xFF000000.toInt()
    }
}
