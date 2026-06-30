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
import android.os.Build
import android.text.TextUtils
import androidx.annotation.WorkerThread
import com.davemorrissey.labs.subscaleview.internal.URI_PATH_ASSET
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_CONTENT
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_FILE
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_RES
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_ZIP
import java.io.File
import java.io.InputStream
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipFile

/**
 * [ImageRegionDecoder] backed by libjpeg-turbo for all JPEG images, and
 * [BitmapRegionDecoder] (Skia) for everything else (WebP, PNG, AVIF, etc.).
 *
 * libjpeg-turbo is faster than Android's built-in JPEG decoder, uses less
 * memory (pure software DCT, no HAL round-trip), and avoids the hardware
 * chroma-upsampler bug present on some Qualcomm/MediaTek devices that produces
 * repeating coloured tint bands on tall strip images.
 *
 * JPEG detection uses magic bytes (FF D8 FF), not the URI extension — so it
 * works correctly for content:// and file+zip:// URIs (CBZ manga archives)
 * where the path has no .jpg extension visible to the caller.
 *
 * Supports all URI schemes used by SSIV: content://, file://, file+zip://
 * (CBZ/ZIP archives), and android.resource://.
 */
public class LiJpegTurboRegionDecoder(
    private val quality: BitmapQuality = BitmapQuality.STANDARD,
) : ImageRegionDecoder {

    private val decoderLock: ReadWriteLock = ReentrantReadWriteLock(true)
    private var decoder: BitmapRegionDecoder? = null

    // True when the source image is a JPEG — detected via magic bytes in init().
    private var isJpeg = false

    // Complete compressed JPEG bytes, read once in init() and kept for tile decodes.
    // null when isJpeg is false (non-JPEG images never need this).
    private var jpegBytes: ByteArray? = null

    // Source image dimensions needed by the native crop math.
    private var imageWidth:  Int = 0
    private var imageHeight: Int = 0

    // URI string key used to evict from byteCache on recycle().
    private var sourceUriKey: String? = null

    // ── init ──────────────────────────────────────────────────────────────────

    @SuppressLint("DiscouragedApi")
    @WorkerThread
    override fun init(context: Context, uri: Uri): Point {
        val brd = openBitmapRegionDecoder(context, uri)
        decoderLock.writeLock().lock()
        try {
            decoder     = brd
            imageWidth  = brd.width
            imageHeight = brd.height
            val key = uri.toString()
            sourceUriKey = key

            // Check the process-level SoftReference cache before reading from disk.
            //
            // reloadImage() — triggered by filter changes — creates a new decoder
            // instance for the same URI. Without caching, every init() allocates a
            // ByteArrayOutputStream that grows to file size (~1–5 MB) and then copies
            // the entire buffer via Arrays.copyOf (the toByteArray() call in the stack
            // trace). On a 2 GB device with only ~200 KB free this causes OOM.
            //
            // SoftReference allows GC to reclaim bytes under memory pressure without
            // any explicit coordination. recycle() performs a best-effort eviction so
            // the map stays bounded to currently-active pages.
            val cached = byteCache[key]?.get()
            if (cached != null) {
                jpegBytes = cached
                isJpeg    = true
            } else {
                val header = readFirstBytes(context, uri, 3)
                if (header != null && isJpegMagic(header)) {
                    val bytes = readBytes(context, uri)
                    if (bytes != null) {
                        jpegBytes = bytes
                        isJpeg    = true
                        byteCache[key] = java.lang.ref.SoftReference(bytes)
                    }
                }
            }
        } finally {
            decoderLock.writeLock().unlock()
        }
        return Point(brd.width, brd.height)
    }

    // ── decodeRegion ──────────────────────────────────────────────────────────

    @WorkerThread
    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        // libjpeg-turbo path for all JPEG images at all zoom levels.
        if (isJpeg) {
            val bytes = jpegBytes
            if (bytes != null) {
                val bmp = nativeDecodeRegion(
                    bytes,
                    imageWidth, imageHeight,
                    sampleSize.coerceAtLeast(1),
                    sRect.left, sRect.top, sRect.right, sRect.bottom,
                )
                if (bmp != null) return bmp
                // Native decode failed — fall through to BitmapRegionDecoder.
            }
        }

        // BitmapRegionDecoder path: non-JPEG images and native fallback.
        decoderLock.readLock().lock()
        return try {
            val dec = decoder
            check(dec?.isRecycled == false) { "Cannot decode region — decoder recycled" }
            checkNotNull(dec) { "Decoder is null" }
            val options = BitmapFactory.Options().apply {
                inSampleSize      = sampleSize.coerceAtLeast(1)
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
        sourceUriKey?.let { byteCache.remove(it) }
        sourceUriKey = null
        jpegBytes    = null
    }

    // ── URI helpers ───────────────────────────────────────────────────────────

    /**
     * Opens a [BitmapRegionDecoder] for all URI schemes supported by SSIV:
     * content://, file://, file+zip:// (CBZ/ZIP archives), android.resource://.
     */
    @SuppressLint("DiscouragedApi")
    private fun openBitmapRegionDecoder(context: Context, uri: Uri): BitmapRegionDecoder {
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
                context.resources.openRawResource(id)
                    .use { makeBrd(it, context, uri) }
            }
            URI_SCHEME_ZIP -> ZipFile(uri.schemeSpecificPart).use { zip ->
                val entry = requireNotNull(zip.getEntry(uri.fragment)) {
                    "Entry ${uri.fragment} not found in zip"
                }
                zip.getInputStream(entry).use { makeBrd(it, context, uri) }
            }
            URI_SCHEME_FILE -> {
                val path = uri.schemeSpecificPart
                if (path.startsWith(URI_PATH_ASSET, ignoreCase = true)) {
                    val name = path.substring(URI_PATH_ASSET.length)
                    context.assets.open(name, AssetManager.ACCESS_RANDOM)
                        .use { makeBrd(it, context, uri) }
                } else {
                    BitmapRegionDecoder(path, context, uri)
                }
            }
            URI_SCHEME_CONTENT -> {
                context.contentResolver.openInputStream(uri)
                    ?.use { makeBrd(it, context, uri) }
                    ?: throw ImageDecodeException.create(context, uri)
            }
            else -> throw UnsupportedUriException(uri.toString())
        }
        return brd
    }

    private fun makeBrd(stream: InputStream, context: Context, uri: Uri): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(stream)
                ?: throw ImageDecodeException.create(context, uri)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(stream, false)
                ?: throw ImageDecodeException.create(context, uri)
        }

    /**
     * Reads the full image bytes for the given URI.
     * Supports content://, file://, file+zip:// and android.resource://.
     * Returns null on any error — isJpeg will remain false and libjpeg-turbo
     * is skipped; the image still displays via BitmapRegionDecoder.
     */
    @SuppressLint("DiscouragedApi")
    private fun readBytes(context: Context, uri: Uri): ByteArray? = try {
        when (uri.scheme) {
            URI_SCHEME_CONTENT ->
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            URI_SCHEME_ZIP -> ZipFile(uri.schemeSpecificPart).use { zip ->
                val entry = zip.getEntry(uri.fragment) ?: return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
            URI_SCHEME_FILE -> {
                val path = uri.schemeSpecificPart
                if (path.startsWith(URI_PATH_ASSET, ignoreCase = true)) {
                    val name = path.substring(URI_PATH_ASSET.length)
                    context.assets.open(name).use { it.readBytes() }
                } else {
                    File(path).readBytes()
                }
            }
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
                context.resources.openRawResource(id).use { it.readBytes() }
            }
            else -> null
        }
    } catch (_: Exception) { null }

    /**
     * Reads only the first [count] bytes of the image at [uri]. Used to peek at the JPEG
     * magic bytes (FF D8 FF) without reading the entire file — avoids allocating and
     * discarding multi-megabyte ByteArrays for every non-JPEG page during [init].
     */
    @SuppressLint("DiscouragedApi")
    private fun readFirstBytes(context: Context, uri: Uri, count: Int): ByteArray? = try {
        fun java.io.InputStream.peekFirst(n: Int): ByteArray? {
            val buf = ByteArray(n)
            return if (read(buf) == n) buf else null
        }
        when (uri.scheme) {
            URI_SCHEME_CONTENT ->
                context.contentResolver.openInputStream(uri)?.use { it.peekFirst(count) }
            URI_SCHEME_ZIP -> ZipFile(uri.schemeSpecificPart).use { zip ->
                val entry = zip.getEntry(uri.fragment) ?: return null
                zip.getInputStream(entry).use { it.peekFirst(count) }
            }
            URI_SCHEME_FILE -> {
                val path = uri.schemeSpecificPart
                if (path.startsWith(URI_PATH_ASSET, ignoreCase = true)) {
                    context.assets.open(path.substring(URI_PATH_ASSET.length)).use { it.peekFirst(count) }
                } else {
                    File(path).inputStream().use { it.peekFirst(count) }
                }
            }
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
                context.resources.openRawResource(id).use { it.peekFirst(count) }
            }
            else -> null
        }
    } catch (_: Exception) { null }

    // ── Factory ───────────────────────────────────────────────────────────────

    public class Factory @JvmOverloads constructor(
        private val quality: BitmapQuality = BitmapQuality.STANDARD,
    ) : DecoderFactory<LiJpegTurboRegionDecoder> {
        // Required by DecoderFactory — applyBitmapConfig() in the app compares
        // this value to decide whether to reinstall the factory.
        override val bitmapConfig: Bitmap.Config
            get() = quality.toBitmapConfig()
        override fun make(): LiJpegTurboRegionDecoder = LiJpegTurboRegionDecoder(quality)
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    public companion object {

        /**
         * Process-level SoftReference cache: URI string → JPEG bytes.
         *
         * Eliminates redundant disk reads when the same URI is decoded by multiple
         * [LiJpegTurboRegionDecoder] instances (e.g. after reloadImage() creates a new
         * decoder for an already-loaded page). SoftReference lets GC reclaim entries
         * under memory pressure. [recycle] explicitly evicts when a page is unloaded.
         */
        private val byteCache =
            java.util.concurrent.ConcurrentHashMap<String, java.lang.ref.SoftReference<ByteArray>>()

        private fun isJpegMagic(bytes: ByteArray): Boolean =
            bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()

        init {
            System.loadLibrary("ssiv_jpeg_decoder")
        }
    }

    // ── JNI ───────────────────────────────────────────────────────────────────

    /**
     * Decodes the tile region [sRectLeft, sRectTop, sRectRight, sRectBottom]
     * (in source image coordinates) from [jpegData] via libjpeg-turbo's
     * tj3SetCroppingRegion API at 1/[sampleSize] scale.
     *
     * Only the MCU blocks overlapping the requested region are decompressed —
     * no full-image decode ever occurs.
     *
     * Returns null on failure — caller falls back to BitmapRegionDecoder.
     */
    private external fun nativeDecodeRegion(
        jpegData:    ByteArray,
        imgWidth:    Int,
        imgHeight:   Int,
        sampleSize:  Int,
        sRectLeft:   Int,
        sRectTop:    Int,
        sRectRight:  Int,
        sRectBottom: Int,
    ): Bitmap?
}
