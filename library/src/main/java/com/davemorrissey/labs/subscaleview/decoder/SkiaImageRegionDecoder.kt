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
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipFile

/**
 * Default [ImageRegionDecoder] using [BitmapRegionDecoder] (Skia).
 *
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
        },
    )

    private var decoder: BitmapRegionDecoder? = null
    private val decoderLock: ReadWriteLock = ReentrantReadWriteLock(true)

    // True when the image is a large JPEG/JPG strip (height >= 5000px).
    // On some devices the hardware JPEG decoder (Qualcomm/MediaTek HAL) has a
    // chroma upsampler bug that produces repeating coloured tint bands on such
    // images. When true, decodeRegion() uses ImageDecoder (API 28+) with
    // ALLOCATOR_SOFTWARE to route decoding through the software JPEG path.
    private var isLargeJpeg = false

    // Context and URI retained from init() for the ImageDecoder software path.
    private var imageContext: Context? = null
    private var imageUri: Uri? = null

    // Full source image dimensions cached so the ImageDecoder path can compute
    // setTargetSize without touching the BitmapRegionDecoder (which requires decodeLock).
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0

    // Caches the last ImageDecoder-decoded full-image bitmap and the sampleSize it
    // was decoded at. All tile requests at the same zoom level reuse this cache;
    // only the crop rect changes. Guarded by overviewLock.
    private val overviewLock = Any()
    private var overviewBitmap: Bitmap? = null
    private var overviewSampleSize: Int = 0

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
        imageContext = context
        imageUri = uri
        imageWidth = brd.width
        imageHeight = brd.height
        isLargeJpeg = brd.height >= LARGE_JPEG_HEIGHT_THRESHOLD && isJpegUri(uri)
        return Point(brd.width, brd.height)
    }

    // ── decodeRegion ──────────────────────────────────────────────────────────

    @WorkerThread
    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        // For large JPEG strip images at sampleSize >= 2, use ImageDecoder with
        // ALLOCATOR_SOFTWARE instead of BitmapRegionDecoder. ImageDecoder routes
        // through the software JPEG path, bypassing the hardware HAL chroma bug.
        // Falls back to BitmapRegionDecoder on failure or when API < 28.
        if (isLargeJpeg && sampleSize >= 2 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val ctx = imageContext
            val u = imageUri
            if (ctx != null && u != null) {
                decodeViaImageDecoder(ctx, u, sRect, sampleSize)?.let { return it }
            }
        }
        decodeLock.lock()
        return try {
            val dec = decoder
            check(dec?.isRecycled == false) { "Cannot decode region — decoder recycled" }
            checkNotNull(dec) { "Decoder is null" }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
                inPreferredConfig = quality.toBitmapConfig()
            }
            dec.decodeRegion(sRect, options)
                ?: error("BitmapRegionDecoder returned null for $sRect")
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

    private val decodeLock: Lock
        get() = decoderLock.readLock()

    // ── ImageDecoder software path (API 28+, large JPEG overview tiles only) ────

    /**
     * Decodes the full image at (imageWidth/sampleSize × imageHeight/sampleSize) using
     * [ImageDecoder.ALLOCATOR_SOFTWARE], caches the result, then returns a crop of that
     * cached bitmap matching [sRect]. All tile requests at the same [sampleSize] reuse
     * the cached full-image bitmap — only one ImageDecoder decode per zoom level.
     *
     * Returns null if the source URI scheme is unsupported or decoding fails;
     * the caller then falls back to [BitmapRegionDecoder].
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeViaImageDecoder(
        context: Context,
        uri: Uri,
        sRect: Rect,
        sampleSize: Int,
    ): Bitmap? {
        val imgW = imageWidth.takeIf { it > 0 } ?: return null
        val imgH = imageHeight.takeIf { it > 0 } ?: return null
        val targetW = (imgW / sampleSize).coerceAtLeast(1)
        val targetH = (imgH / sampleSize).coerceAtLeast(1)
        return synchronized(overviewLock) {
            // Decode the full image only when sampleSize changes or cache is empty.
            if (overviewSampleSize != sampleSize || overviewBitmap?.isRecycled != false) {
                overviewBitmap?.recycle()
                overviewBitmap = null
                try {
                    val source = createImageDecoderSource(context, uri)
                        ?: return@synchronized null
                    overviewBitmap = ImageDecoder.decodeBitmap(source) { dec, _, _ ->
                        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        dec.setTargetSize(targetW, targetH)
                    }
                    overviewSampleSize = sampleSize
                } catch (e: Exception) {
                    return@synchronized null
                }
            }
            val bmp = overviewBitmap ?: return@synchronized null
            // Compute crop rect in decoded-image coordinates and return a new independent
            // Bitmap. createBitmap() is called inside the lock so bmp cannot be recycled
            // by a concurrent recycle() call while we read it.
            val fw = bmp.width
            val fh = bmp.height
            val cropX = (sRect.left.toFloat() * fw / imgW).toInt().coerceIn(0, fw - 1)
            val cropY = (sRect.top.toFloat() * fh / imgH).toInt().coerceIn(0, fh - 1)
            val cropW = ((sRect.right.toFloat() * fw / imgW).toInt() - cropX)
                .coerceIn(1, fw - cropX)
            val cropH = ((sRect.bottom.toFloat() * fh / imgH).toInt() - cropY)
                .coerceIn(1, fh - cropY)
            Bitmap.createBitmap(bmp, cropX, cropY, cropW, cropH)
        }
    }

    /**
     * Creates an [ImageDecoder.Source] for content:// and file:// URIs.
     * Returns null for res:// and zip:// URIs (rare; falls back to BitmapRegionDecoder).
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun createImageDecoderSource(context: Context, uri: Uri): ImageDecoder.Source? = try {
        when (uri.scheme) {
            URI_SCHEME_CONTENT -> ImageDecoder.createSource(context.contentResolver, uri)
            URI_SCHEME_FILE -> {
                val path = uri.schemeSpecificPart
                if (path.startsWith(URI_PATH_ASSET, ignoreCase = true)) {
                    val assetName = path.substring(URI_PATH_ASSET.length)
                    val bytes = context.assets.open(assetName).use { it.readBytes() }
                    ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                } else {
                    ImageDecoder.createSource(File(path))
                }
            }
            else -> null
        }
    } catch (e: Exception) { null }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private companion object {
        /** Images taller than this (in source pixels) are considered large strips. */
        private const val LARGE_JPEG_HEIGHT_THRESHOLD = 5000

        /** Returns true if the URI path ends with .jpg or .jpeg (case-insensitive). */
        private fun isJpegUri(uri: Uri): Boolean {
            val path = uri.path?.lowercase() ?: return false
            return path.endsWith(".jpg") || path.endsWith(".jpeg")
        }
    }

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
            },
        )

        override val bitmapConfig: Bitmap.Config
            get() = quality.toBitmapConfig()

        override fun make(): SkiaImageRegionDecoder = SkiaImageRegionDecoder(quality)
    }
}
