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
 * Default [ImageRegionDecoder] using [BitmapRegionDecoder] (Skia).
 *
 * ### Chroma block alignment — green/blue tint fix (all formats, all Android versions)
 *
 * JPEG and WEBP lossy encode colour in 16×16 YCbCr minimum coding units (MCUs). When a
 * tile boundary does not fall on a 16-pixel multiple, [BitmapRegionDecoder] reads chroma
 * samples from the wrong MCU — producing green/blue horizontal bands on large strip images.
 *
 * When [BitmapRegionDecoder.decodeRegion] is called with `inSampleSize > 1`, the decoder
 * processes pixel groups of size `inSampleSize × inSampleSize`. The effective alignment
 * requirement becomes `inSampleSize × 16`. This decoder uses
 * `effectiveChromaBlockSize(inSampleSize)` to compute the correct alignment per decode call.
 *
 * Fix: expand the tile rect to the effective block boundary, decode the aligned region,
 * then crop the result back to the original pixels. Maximum overhead: ~15 × inSampleSize
 * extra pixels per edge — negligible vs. tile sizes of 256–512 px.
 *
 * ### Color quality
 *
 * Defaults to [BitmapQuality.STANDARD] (ARGB_8888). RGBA_F16 (HIGH) has been removed.
 * Use [BitmapQuality.MEMORY_SAVING] (RGB_565) only on low-RAM devices.
 *
 * ### Why HARDWARE bitmap is not used
 *
 * Hardware bitmaps cannot be drawn on a software [android.graphics.Canvas]. Several
 * render paths in the app (gesture layers, screenshot, shared elements) force software
 * rendering, causing a silent `IllegalStateException` that results in solid black tiles.
 */
public class SkiaImageRegionDecoder @JvmOverloads constructor(
    private val quality: BitmapQuality = BitmapQuality.STANDARD,
) : ImageRegionDecoder {

    @Deprecated("Use BitmapQuality constructor.")
    public constructor(bitmapConfig: Bitmap.Config) : this(
        quality = when (bitmapConfig) {
            Bitmap.Config.RGB_565 -> BitmapQuality.MEMORY_SAVING
            // RGBA_F16 (HIGH) removed; fall back to STANDARD (ARGB_8888).
            else -> BitmapQuality.STANDARD
            // unreachable but needed to suppress warning:
            else -> BitmapQuality.STANDARD
        },
    )

    private var decoder: BitmapRegionDecoder? = null
    private val decoderLock: ReadWriteLock = ReentrantReadWriteLock(true)

    /** Full image width — needed to clamp alignment expansion to the image boundary. */
    private var imageWidth: Int = 0

    /** Full image height — needed to clamp alignment expansion to the image boundary. */
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

            val effectiveSampleSize = sampleSize.coerceAtLeast(1)
            val options = BitmapFactory.Options().apply {
                inSampleSize = effectiveSampleSize
                inPreferredConfig = quality.toBitmapConfig()
                // inScaled = false: prevent Android from applying display density scaling
                // to the decoded bitmap, which would alter pixel dimensions unexpectedly.
                inScaled = false
            }

            // Compute the effective chroma block size for this decode.
            // For inSampleSize=1: block=16. For inSampleSize=2: block=32. Etc.
            // Using the larger block ensures correct chroma alignment even when the
            // decoder processes multiple MCUs per output sample.
            val blockSize = effectiveChromaBlockSize(effectiveSampleSize)

            // Fast path: all edges already on block boundaries → no overhead.
            if (sRect.isChromaAligned(imageWidth, imageHeight, blockSize)) {
                return dec.decodeRegion(sRect, options)
                    ?: error("BitmapRegionDecoder returned null for $sRect — " +
                        "image may be corrupted or unsupported on this device")
            }

            // Slow path: expand → decode → crop to remove chroma corruption.
            val aligned = sRect.alignToChromaBlocks(imageWidth, imageHeight, blockSize)
            val rawBitmap = dec.decodeRegion(aligned, options)
                ?: error("BitmapRegionDecoder returned null for aligned region $aligned")

            cropToRequestedRegion(rawBitmap, sRect, aligned, effectiveSampleSize)
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
                // RGBA_F16 (HIGH) removed; fall back to STANDARD (ARGB_8888).
            else -> BitmapQuality.STANDARD
            // unreachable but needed to suppress warning:
                else -> BitmapQuality.STANDARD
            },
        )

        override val bitmapConfig: Bitmap.Config
            get() = quality.toBitmapConfig()

        override fun make(): SkiaImageRegionDecoder = SkiaImageRegionDecoder(quality)
    }
}
