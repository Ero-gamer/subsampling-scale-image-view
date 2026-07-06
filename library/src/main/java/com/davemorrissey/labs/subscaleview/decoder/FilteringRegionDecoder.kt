package com.davemorrissey.labs.subscaleview.decoder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import androidx.annotation.WorkerThread
import com.davemorrissey.labs.subscaleview.ImageSource
import kotlin.math.abs

/**
 * [ImageRegionDecoder] wrapper that applies CPU denoise → sharpen → vibrance → dither/grain
 * to every decoded tile in a single getPixels/loop/setPixels pass.
 *
 * Pipeline order: denoise (when sharpening active) → sharpen → vibrance → dither+grain.
 * All filters use thread-local IntArray pools — no per-tile heap allocations in the hot path.
 */
public class FilteringRegionDecoder(
    private val inner: ImageRegionDecoder,
    private val sharpening: Float,
    private val vibrance: Float,
    private val denoise: Float,
    private val dither: Float,
    private val grain: Float,
) : ImageRegionDecoder {

    override fun init(context: Context, uri: Uri): Point = inner.init(context, uri)
    override fun init(context: Context, source: ImageSource): Point = inner.init(context, source)

    @WorkerThread
    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        val tile = inner.decodeRegion(sRect, sampleSize)
        // Defensive catch: SSIV swallows any Throwable from decodeRegion() into a no-op
        // onTileLoadError and never retries the tile, permanently leaving the unfiltered base
        // bitmap visible. Falling back to the unfiltered tile keeps the image readable.
        return try {
            applyFilters(tile)
        } catch (e: Throwable) {
            tile
        }
    }

    override val isReady: Boolean get() = inner.isReady
    override fun recycle() = inner.recycle()

    // ── filter math ──────────────────────────────────────────────────────────

    private fun applyFilters(tile: Bitmap): Bitmap {
        val doSharpen  = sharpening > 0.01f
        val doVibrance = vibrance != 0f
        val doDenoise  = denoise > 0.01f && doSharpen
        val doDither   = dither > 0.01f
        val doGrain    = grain > 0.01f
        if (!doSharpen && !doVibrance && !doDither && !doGrain) return tile

        val w = tile.width
        val h = tile.height
        if (w <= 0 || h <= 0) return tile

        val needsCopy = tile.config != Bitmap.Config.ARGB_8888 || !tile.isMutable
        // Guard: Bitmap.copy() CAN return null on OOM. Do NOT recycle tile until copy confirmed.
        val working = if (needsCopy) tile.copy(Bitmap.Config.ARGB_8888, true) ?: tile else tile

        val pixelCount = w * h
        val src = srcPool.acquire(pixelCount)
        working.getPixels(src, 0, w, 0, 0, w, h)

        val k   = if (doSharpen) sharpening.coerceIn(0f, 1f) * SHARPEN_SCALAR else 0f
        val out = if (doSharpen) outPool.acquire(pixelCount) else src
        val ditherGrainTable = if (doDither || doGrain) buildDitherGrainTable(dither, grain) else null

        for (y in 0 until h) {
            val rowStart = y * w
            val hasAbove = y > 0
            val hasBelow = y < h - 1
            for (x in 0 until w) {
                val idx = rowStart + x
                val px  = src[idx]
                var r: Int; var g: Int; var b: Int

                if (doSharpen && x > 0 && x < w - 1 && hasAbove && hasBelow) {
                    val top    = src[idx - w]; val bottom = src[idx + w]
                    val left   = src[idx - 1]; val right  = src[idx + 1]
                    val cr = (px shr 16) and 0xFF; val cg = (px shr 8) and 0xFF; val cb = px and 0xFF
                    val tr  = (top shr 16) and 0xFF;    val tg  = (top shr 8) and 0xFF;    val tb  = top and 0xFF
                    val brr = (bottom shr 16) and 0xFF; val brg = (bottom shr 8) and 0xFF; val brb = bottom and 0xFF
                    val lr  = (left shr 16) and 0xFF;   val lg  = (left shr 8) and 0xFF;   val lb  = left and 0xFF
                    val rr  = (right shr 16) and 0xFF;  val rg  = (right shr 8) and 0xFF;  val rb  = right and 0xFF
                    val dr = if (doDenoise) denoiseChannel(cr, tr, brr, lr, rr) else cr
                    val dg = if (doDenoise) denoiseChannel(cg, tg, brg, lg, rg) else cg
                    val db = if (doDenoise) denoiseChannel(cb, tb, brb, lb, rb) else cb
                    r = sharpenChannel(dr, tr, brr, lr, rr, k)
                    g = sharpenChannel(dg, tg, brg, lg, rg, k)
                    b = sharpenChannel(db, tb, brb, lb, rb, k)
                } else {
                    r = (px shr 16) and 0xFF; g = (px shr 8) and 0xFF; b = px and 0xFF
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

                if (ditherGrainTable != null) {
                    val noise = ditherGrainTable[((y and 63) shl 6) or (x and 63)]
                    if (noise != 0) {
                        r = (r + noise).coerceIn(0, 255)
                        g = (g + noise).coerceIn(0, 255)
                        b = (b + noise).coerceIn(0, 255)
                    }
                }

                out[idx] = (px and ALPHA_MASK) or (r shl 16) or (g shl 8) or b
            }
        }

        working.setPixels(out, 0, w, 0, 0, w, h)
        // Recycle ONLY after setPixels succeeds — ensures catch-block fallback is still valid.
        if (needsCopy && working !== tile) tile.recycle()
        return working
    }

    private fun denoiseChannel(center: Int, top: Int, bottom: Int, left: Int, right: Int): Int {
        val wT = 1f / (1f + abs(center - top)    * DENOISE_FALLOFF)
        val wB = 1f / (1f + abs(center - bottom) * DENOISE_FALLOFF)
        val wL = 1f / (1f + abs(center - left)   * DENOISE_FALLOFF)
        val wR = 1f / (1f + abs(center - right)  * DENOISE_FALLOFF)
        val wSum = 1f + wT + wB + wL + wR
        val denoised = (center + top * wT + bottom * wB + left * wL + right * wR) / wSum
        val moved = center + DENOISE_STRENGTH * (denoised - center)
        return when { moved <= 0f -> 0; moved >= 255f -> 255; else -> (moved + 0.5f).toInt() }
    }

    private fun sharpenChannel(center: Int, top: Int, bottom: Int, left: Int, right: Int, k: Float): Int {
        val v = center + k * (4 * center - top - bottom - left - right)
        return when { v <= 0f -> 0; v >= 255f -> 255; else -> (v + 0.5f).toInt() }
    }

    private fun vibranceFactor(r: Int, g: Int, b: Int, v: Float): Float {
        if (v == 0f) return 1f
        val maxC = maxOf(r, g, b); val minC = minOf(r, g, b); val delta = maxC - minC
        if (delta == 0) return 1f
        val lSum = maxC + minC
        val denom = (if (lSum <= 255) lSum else 510 - lSum).coerceAtLeast(1)
        val sat = delta.toFloat() / denom
        val sel = (1f - sat) * (1f - sat)
        return 1f + v.coerceIn(-1f, 1f) * sel * VIBRANCE_SCALAR
    }

    private fun clamp255(v: Float): Int = when { v <= 0f -> 0; v >= 255f -> 255; else -> (v + 0.5f).toInt() }

    public class Factory(
        private val innerFactory: DecoderFactory<out ImageRegionDecoder>,
        val sharpening: Float,
        val vibrance: Float,
        val denoise: Float = 0f,
        val dither: Float = 0f,
        val grain: Float = 0f,
    ) : DecoderFactory<FilteringRegionDecoder> {
        override val bitmapConfig: Bitmap.Config? get() = innerFactory.bitmapConfig
        override fun make(): FilteringRegionDecoder =
            FilteringRegionDecoder(innerFactory.make(), sharpening, vibrance, denoise, dither, grain)
    }

    private companion object {
        private const val SHARPEN_SCALAR   = 0.5f
        private const val VIBRANCE_SCALAR  = 1.5f
        private const val DENOISE_STRENGTH = 0.6f
        private const val DENOISE_FALLOFF  = 0.15f
        private val ALPHA_MASK = 0xFF000000.toInt()

        private val srcPool = ThreadLocal<IntArray>()
        private val outPool = ThreadLocal<IntArray>()

        private fun ThreadLocal<IntArray>.acquire(minSize: Int): IntArray {
            val ex = get()
            if (ex != null && ex.size >= minSize) return ex
            var cap = minSize.coerceAtLeast(1024)
            cap = Integer.highestOneBit(cap - 1) shl 1
            return IntArray(cap).also { set(it) }
        }

        private fun buildDitherGrainTable(dither: Float, grain: Float): IntArray {
            val bayer8 = intArrayOf(
                0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26,
                12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
                3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25,
                15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21,
            )
            val rand = java.util.Random(0xC0FFEEL)
            val dMax = dither * 3f; val gMax = grain * 5f
            return IntArray(64 * 64) { i ->
                val x = i and 63; val y = i shr 6
                val d = (bayer8[(y % 8) * 8 + (x % 8)] / 63f - 0.5f) * dMax
                val g = (rand.nextFloat() - 0.5f) * gMax
                (d + g).toInt().coerceIn(-8, 8)
            }
        }
    }
}
