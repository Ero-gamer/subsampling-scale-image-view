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

            // Detect JPEG by magic bytes — works for all URI schemes including
            // content:// (no extension) and file+zip:// (CBZ archives).
            val bytes = readBytes(context, uri)
            if (bytes != null && isJpegMagic(bytes)) {
                jpegBytes = bytes
                isJpeg    = true
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
        jpegBytes = null
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

    // ── Factory ───────────────────────────────────────────────────────────────

    public class Factory @JvmOverloads constructor(
        private val quality: BitmapQuality = BitmapQuality.STANDARD,
    ) : DecoderFactory<LiJpegTurboRegionDecoder> {
        override fun make(): LiJpegTurboRegionDecoder = LiJpegTurboRegionDecoder(quality)
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    public companion object {

        /**
         * Detects JPEG by magic bytes FF D8 FF — works for all URI schemes
         * regardless of file extension.
         */
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
