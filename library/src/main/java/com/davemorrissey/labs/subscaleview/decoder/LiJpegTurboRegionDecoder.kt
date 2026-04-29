package com.davemorrissey.labs.subscaleview.decoder

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.WorkerThread
import com.davemorrissey.labs.subscaleview.internal.URI_PATH_ASSET
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_CONTENT
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_FILE
import java.io.File
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * [ImageRegionDecoder] that transparently routes large JPEG strip images through
 * libjpeg-turbo (via JNI) to bypass the hardware JPEG chroma-upsampler bug present
 * on some Android devices (Qualcomm/MediaTek HAL) that produces repeating coloured
 * tint bands on images with height ≥ [LARGE_JPEG_HEIGHT_THRESHOLD] px.
 *
 * All other images — WebP, PNG, AVIF, small JPEGs, and full-resolution tiles at
 * sampleSize=1 — are decoded by the standard [android.graphics.BitmapRegionDecoder]
 * (Skia/hardware path) with zero overhead.
 *
 * The libjpeg-turbo path decodes the full image at (1/sampleSize) scale using
 * software DCT, caches the result per zoom level, and crops each tile from the
 * cache — matching the tile dimensions SSIV expects. The cache is invalidated
 * whenever sampleSize changes (i.e. the user zooms to a different overview level).
 */
public class LiJpegTurboRegionDecoder(
    private val quality: BitmapQuality = BitmapQuality.STANDARD,
) : ImageRegionDecoder {

    private val decoderLock: ReadWriteLock = ReentrantReadWriteLock(true)
    private var decoder: android.graphics.BitmapRegionDecoder? = null

    // True when the source is a JPEG strip tall enough to trigger the hardware bug.
    private var isLargeJpeg = false

    // Retained for the libjpeg-turbo decode path.
    private var imageContext: Context? = null
    private var imageUri: Uri? = null
    private var imageWidth:  Int = 0
    private var imageHeight: Int = 0

    // Overview bitmap cache: one full-image decode (at 1/sampleSize scale) is cached
    // and reused for all tile crops at the same zoom level. Guarded by overviewLock.
    private val overviewLock = Any()
    private var overviewBitmap: Bitmap? = null
    private var overviewSampleSize: Int = 0

    // ── init ──────────────────────────────────────────────────────────────────

    @WorkerThread
    override fun init(context: Context, uri: Uri): Point {
        val brd = openBitmapRegionDecoder(context, uri)
        decoderLock.writeLock().lock()
        try {
            decoder = brd
            imageContext = context
            imageUri = uri
            imageWidth  = brd.width
            imageHeight = brd.height
            isLargeJpeg = brd.height >= LARGE_JPEG_HEIGHT_THRESHOLD && isJpegUri(uri)
        } finally {
            decoderLock.writeLock().unlock()
        }
        return Point(brd.width, brd.height)
    }

    // ── decodeRegion ──────────────────────────────────────────────────────────

    @WorkerThread
    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        // libjpeg-turbo path: large JPEG, overview tiles only (sampleSize >= 2).
        // sampleSize=1 (full-resolution tiles) keeps using BitmapRegionDecoder —
        // decoding the full multi-MB image at 1:1 just to crop a 512px tile is
        // not practical even with libjpeg-turbo.
        if (isLargeJpeg && sampleSize >= 2) {
            val ctx = imageContext
            val u   = imageUri
            if (ctx != null && u != null) {
                decodeViaLibjpegTurbo(ctx, u, sRect, sampleSize)?.let { return it }
            }
        }
        // Standard BitmapRegionDecoder path (all non-large-JPEG, sampleSize=1, fallback).
        decoderLock.readLock().lock()
        return try {
            val dec = decoder
            check(dec?.isRecycled == false) { "Cannot decode region — decoder recycled" }
            checkNotNull(dec) { "Decoder is null" }
            val options = BitmapFactory.Options().apply {
                inSampleSize     = sampleSize.coerceAtLeast(1)
                inPreferredConfig = quality.toBitmapConfig()
            }
            dec.decodeRegion(sRect, options)
                ?: error("BitmapRegionDecoder returned null for $sRect")
        } finally {
            decoderLock.readLock().unlock()
        }
    }

    // ── isReady / recycle ─────────────────────────────────────────────────────

    override val isReady: Boolean
        get() = decoder?.isRecycled == false

    override fun recycle() {
        decoderLock.writeLock().lock()
        try {
            decoder?.recycle()
            decoder = null
            imageContext = null
            imageUri = null
        } finally {
            decoderLock.writeLock().unlock()
        }
        synchronized(overviewLock) {
            overviewBitmap?.recycle()
            overviewBitmap = null
        }
    }

    // ── libjpeg-turbo helpers ─────────────────────────────────────────────────

    /**
     * Decodes the full image at (imageWidth/sampleSize × imageHeight/sampleSize)
     * using the native libjpeg-turbo software decoder via JNI. The result is cached
     * in [overviewBitmap]; subsequent tile requests at the same [sampleSize] reuse
     * the cache and only perform a [Bitmap.createBitmap] crop.
     *
     * Returns null on any failure — the caller falls back to [BitmapRegionDecoder].
     */
    private fun decodeViaLibjpegTurbo(
        context: Context,
        uri: Uri,
        sRect: Rect,
        sampleSize: Int,
    ): Bitmap? {
        val imgW = imageWidth.takeIf  { it > 0 } ?: return null
        val imgH = imageHeight.takeIf { it > 0 } ?: return null

        return synchronized(overviewLock) {
            // Decode full image only when zoom level (sampleSize) changes.
            if (overviewSampleSize != sampleSize || overviewBitmap?.isRecycled != false) {
                overviewBitmap?.recycle()
                overviewBitmap = null

                val jpegBytes = readJpegBytes(context, uri) ?: return@synchronized null

                // Call native libjpeg-turbo decode — returns a full (imgW/s × imgH/s) Bitmap.
                // The sRect is passed so the native layer can derive the crop from the same
                // scaled-image coordinates rather than allocating a separate crop buffer.
                // Here we pass the full-image bounds (0,0,imgW,imgH) to get the full scaled
                // overview, then crop below in Kotlin.
                val bmp = nativeDecodeRegion(
                    jpegBytes,
                    imgW, imgH,
                    sampleSize,
                    0, 0, imgW, imgH,   // full image — crop is done in Kotlin below
                ) ?: return@synchronized null

                overviewBitmap     = bmp
                overviewSampleSize = sampleSize
            }

            val bmp = overviewBitmap ?: return@synchronized null
            val fw = bmp.width
            val fh = bmp.height

            // Crop the requested tile out of the cached overview bitmap.
            val cropX = (sRect.left.toFloat()   * fw / imgW).toInt().coerceIn(0, fw - 1)
            val cropY = (sRect.top.toFloat()    * fh / imgH).toInt().coerceIn(0, fh - 1)
            val cropW = ((sRect.right.toFloat() * fw / imgW).toInt() - cropX).coerceIn(1, fw - cropX)
            val cropH = ((sRect.bottom.toFloat()* fh / imgH).toInt() - cropY).coerceIn(1, fh - cropY)
            Bitmap.createBitmap(bmp, cropX, cropY, cropW, cropH)
        }
    }

    /** Reads the full JPEG file into memory. Returns null on any I/O error. */
    private fun readJpegBytes(context: Context, uri: Uri): ByteArray? = try {
        when (uri.scheme) {
            URI_SCHEME_CONTENT ->
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            URI_SCHEME_FILE -> {
                val path = uri.schemeSpecificPart
                if (path.startsWith(URI_PATH_ASSET, ignoreCase = true)) {
                    val assetName = path.substring(URI_PATH_ASSET.length)
                    context.assets.open(assetName).use { it.readBytes() }
                } else {
                    File(path).readBytes()
                }
            }
            else -> null
        }
    } catch (e: Exception) { null }

    // ── Factory ───────────────────────────────────────────────────────────────

    public class Factory @JvmOverloads constructor(
        private val quality: BitmapQuality = BitmapQuality.STANDARD,
    ) : DecoderFactory<LiJpegTurboRegionDecoder> {
        override fun make(): LiJpegTurboRegionDecoder = LiJpegTurboRegionDecoder(quality)
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    public companion object {

        /** Images taller than this (source pixels) trigger the libjpeg-turbo path. */
        private const val LARGE_JPEG_HEIGHT_THRESHOLD = 5000

        /** Returns true if the URI path ends in .jpg or .jpeg (case-insensitive). */
        private fun isJpegUri(uri: Uri): Boolean {
            val path = uri.path?.lowercase() ?: return false
            return path.endsWith(".jpg") || path.endsWith(".jpeg")
        }

        init {
            System.loadLibrary("ssiv_jpeg_decoder")
        }
    }

    // ── JNI ───────────────────────────────────────────────────────────────────

    /**
     * Decodes [jpegData] via libjpeg-turbo at (imgWidth/sampleSize × imgHeight/sampleSize)
     * and returns a crop of the decoded image defined by [cropLeft/Top/Right/Bottom]
     * in source-image pixel coordinates.
     *
     * Returns null if the native decode fails — Kotlin caller falls back to
     * [BitmapRegionDecoder].
     */
    private external fun nativeDecodeRegion(
        jpegData:   ByteArray,
        imgWidth:   Int,
        imgHeight:  Int,
        sampleSize: Int,
        cropLeft:   Int,
        cropTop:    Int,
        cropRight:  Int,
        cropBottom: Int,
    ): Bitmap?
}

/** Creates a [BitmapRegionDecoder] for the given URI, supporting content://, file://, and assets. */
private fun openBitmapRegionDecoder(context: Context, uri: Uri): android.graphics.BitmapRegionDecoder {
    val stream = when (uri.scheme) {
        URI_SCHEME_CONTENT ->
            context.contentResolver.openInputStream(uri)
        URI_SCHEME_FILE -> {
            val path = uri.schemeSpecificPart
            if (path.startsWith(URI_PATH_ASSET, ignoreCase = true)) {
                val assetName = path.substring(URI_PATH_ASSET.length)
                context.assets.open(assetName)
            } else {
                File(path).inputStream()
            }
        }
        else -> throw UnsupportedUriException(uri)
    } ?: throw IllegalStateException("Cannot open stream for $uri")

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        stream.use { android.graphics.BitmapRegionDecoder.newInstance(it) }
            ?: throw IllegalStateException("BitmapRegionDecoder.newInstance returned null")
    } else {
        @Suppress("DEPRECATION")
        stream.use { android.graphics.BitmapRegionDecoder.newInstance(it, false) }
            ?: throw IllegalStateException("BitmapRegionDecoder.newInstance returned null")
    }
}
