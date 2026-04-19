package com.davemorrissey.labs.subscaleview.decoder

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.text.TextUtils
import androidx.annotation.WorkerThread
import com.davemorrissey.labs.subscaleview.internal.URI_PATH_ASSET
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_CONTENT
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_FILE
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_RES
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_ZIP
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipFile

/**
 * Default [ImageRegionDecoder] implementation using [BitmapRegionDecoder] (Skia).
 *
 * ### Chroma alignment fix — green/blue bars on large images
 *
 * **The problem.** JPEG 4:2:0 and WEBP lossy encode color using 16×16 chroma minimum
 * coding units (MCUs). Android's [BitmapRegionDecoder] does NOT align requested tile
 * rects to these boundaries before decoding. When a tile top or left edge falls on a
 * non-multiple-of-16 row/column, the Cb and Cr chroma planes are read starting from
 * the wrong sample, producing the distinctive **green or blue horizontal bars** visible
 * across large images (typically 1400×10000+). This is a Skia/Android limitation present
 * on all API levels and affects JPEG, WEBP lossy, and any other 4:2:0 format.
 *
 * **The fix.** Before calling [BitmapRegionDecoder.decodeRegion], expand all four edges
 * of the requested rectangle outward to the nearest 16-pixel boundary, clamped to the
 * image dimensions. After decoding the (slightly larger) aligned region, crop the
 * resulting bitmap back to the originally requested area. Maximum alignment expansion:
 * 15 pixels per edge — negligible compared to typical tile sizes of 256–512px.
 *
 * If a tile rect is already aligned, no expansion or crop is performed (fast path).
 *
 * ### Color quality
 *
 * Default: [BitmapQuality.STANDARD] (ARGB_8888) — 32 bpp, correct colors, full alpha.
 * [BitmapQuality.HIGH] selects RGBA_F16 on API 26+ for wide-gamut HDR.
 * [BitmapQuality.MEMORY_SAVING] selects RGB_565 for constrained RAM.
 *
 * ### HARDWARE bitmap NOT used
 *
 * Hardware bitmaps ([Bitmap.Config.HARDWARE]) are not used for tile decoding.
 * Hardware bitmaps cannot be drawn on a software [android.graphics.Canvas], which causes
 * a silent `IllegalStateException` that manifests as a solid black tile. Several render
 * paths in SSIV (downsampling transitions, zoom resets) and the surrounding app
 * (WebtoonScalingFrame's software layer during gestures) use software canvas contexts.
 */
public class SkiaImageRegionDecoder @JvmOverloads constructor(
    private val quality: BitmapQuality = BitmapQuality.STANDARD,
) : ImageRegionDecoder {

    // ── Backwards-compat constructor (accepts Bitmap.Config directly) ──────────
    @Suppress("DEPRECATION")
    @Deprecated("Use BitmapQuality constructor.")
    public constructor(bitmapConfig: Bitmap.Config) : this(
        quality = when (bitmapConfig) {
            Bitmap.Config.RGB_565 -> BitmapQuality.MEMORY_SAVING
            Bitmap.Config.RGBA_F16 -> BitmapQuality.HIGH
            else -> BitmapQuality.STANDARD
        },
    )

    private var decoder: BitmapRegionDecoder? = null
    private val decoderLock: ReadWriteLock = ReentrantReadWriteLock(true)

    // Full image dimensions — required to clamp alignment expansion to image bounds.
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0

    // ── init ──────────────────────────────────────────────────────────────────

    @SuppressLint("DiscouragedApi")
    @Throws(Exception::class)
    @WorkerThread
    override fun init(context: Context, uri: Uri): Point {
        val brd: BitmapRegionDecoder = when (uri.scheme) {
            URI_SCHEME_RES -> {
                val packageName = uri.authority
                val res = if (packageName == null || context.packageName == packageName) {
                    context.resources
                } else {
                    context.packageManager.getResourcesForApplication(packageName)
                }
                var id = 0
                val segments = uri.pathSegments
                if (segments.size == 2 && segments[0] == "drawable") {
                    id = res.getIdentifier(segments[1], "drawable", packageName)
                } else if (segments.size == 1 && TextUtils.isDigitsOnly(segments[0])) {
                    try { id = segments[0].toInt() } catch (_: NumberFormatException) {}
                }
                context.resources.openRawResource(id).use { BitmapRegionDecoder(it, context, uri) }
            }

            URI_SCHEME_ZIP -> ZipFile(uri.schemeSpecificPart).use { file ->
                val entry = requireNotNull(file.getEntry(uri.fragment)) {
                    "Entry ${uri.fragment} not found in the zip"
                }
                file.getInputStream(entry).use { BitmapRegionDecoder(it, context, uri) }
            }

            URI_SCHEME_FILE -> {
                val path = uri.schemeSpecificPart
                if (path.startsWith(URI_PATH_ASSET, ignoreCase = true)) {
                    val assetName = path.substring(URI_PATH_ASSET.length)
                    context.assets.open(assetName, AssetManager.ACCESS_RANDOM)
                        .use { BitmapRegionDecoder(it, context, uri) }
                } else {
                    BitmapRegionDecoder(path, context, uri)
                }
            }

            URI_SCHEME_CONTENT -> {
                context.contentResolver.openInputStream(uri)
                    ?.use { BitmapRegionDecoder(it, context, uri) }
                    ?: throw ImageDecodeException.create(context, uri)
            }

            else -> throw UnsupportedUriException(uri.toString())
        }

        decoder = brd
        imageWidth = brd.width
        imageHeight = brd.height
        return Point(imageWidth, imageHeight)
    }

    // ── decodeRegion ──────────────────────────────────────────────────────────

    @WorkerThread
    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        decodeLock.lock()
        return try {
            val dec = decoder
            check(dec?.isRecycled == false) { "Cannot decode region — decoder recycled" }
            checkNotNull(dec) { "Decoder is null" }

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
                inPreferredConfig = quality.toBitmapConfig()
                // inMutable = false — tiles are read-only; no need to allocate mutable backing.
                // This allows Skia to use more efficient internal memory layouts on some devices.
            }

            // Fast path: if the tile rect is already chroma-aligned, skip expand+crop.
            if (sRect.isChromaAligned(imageWidth, imageHeight)) {
                return dec.decodeRegion(sRect, options)
                    ?: error("BitmapRegionDecoder returned null for $sRect — " +
                        "image may be corrupted or unsupported on this device")
            }

            // Slow path (the common case for large images): expand → decode → crop.
            val aligned = sRect.alignToChromaBlocks(imageWidth, imageHeight)
            val rawBitmap = dec.decodeRegion(aligned, options)
                ?: error("BitmapRegionDecoder returned null for aligned region $aligned — " +
                    "image may be corrupted or too large for this device's GPU")

            cropToRequestedRegion(rawBitmap, sRect, aligned, options.inSampleSize)
        } finally {
            decodeLock.unlock()
        }
    }

    // ── isReady / recycle ─────────────────────────────────────────────────────

    override val isReady: Boolean
        @Synchronized get() = decoder?.isRecycled == false

    @Synchronized
    override fun recycle() {
        decoderLock.writeLock().lock()
        try {
            decoder?.recycle()
            decoder = null
        } finally {
            decoderLock.writeLock().unlock()
        }
    }

    private val decodeLock: Lock
        get() = decoderLock.readLock()

    // ── Factory ───────────────────────────────────────────────────────────────

    public class Factory @JvmOverloads constructor(
        public val quality: BitmapQuality = BitmapQuality.STANDARD,
    ) : DecoderFactory<SkiaImageRegionDecoder> {

        @Deprecated("Use BitmapQuality constructor.")
        public constructor(bitmapConfig: Bitmap.Config) : this(
            quality = when (bitmapConfig) {
                Bitmap.Config.RGB_565 -> BitmapQuality.MEMORY_SAVING
                Bitmap.Config.RGBA_F16 -> BitmapQuality.HIGH
                else -> BitmapQuality.STANDARD
            },
        )

        override val bitmapConfig: Bitmap.Config
            get() = quality.toBitmapConfig()

        override fun make(): SkiaImageRegionDecoder = SkiaImageRegionDecoder(quality)
    }
}
