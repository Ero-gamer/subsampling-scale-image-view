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

public class SkiaImageRegionDecoder @JvmOverloads constructor(
    private val quality: BitmapQuality = BitmapQuality.STANDARD,
) : ImageRegionDecoder {

    @Deprecated("Use BitmapQuality constructor.")
    public constructor(bitmapConfig: Bitmap.Config) : this(
        quality = when (bitmapConfig) {
            Bitmap.Config.RGB_565 -> BitmapQuality.MEMORY_SAVING
            else -> BitmapQuality.STANDARD
        },
    )

    private var decoder: BitmapRegionDecoder? = null
    private val decoderLock: ReadWriteLock = ReentrantReadWriteLock(true)

    private val decodeLock: Lock
        get() = decoderLock.readLock()

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
        return Point(brd.width, brd.height)
    }

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
            }
            dec.decodeRegion(sRect, options)
                ?: error("BitmapRegionDecoder returned null for $sRect")
        } finally {
            decodeLock.unlock()
        }
    }

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

    public class Factory @JvmOverloads constructor(
        private val quality: BitmapQuality = BitmapQuality.STANDARD,
    ) : DecoderFactory<SkiaImageRegionDecoder> {

        @Deprecated("Use BitmapQuality constructor.")
        public constructor(bitmapConfig: Bitmap.Config) : this(
            quality = when (bitmapConfig) {
                Bitmap.Config.RGB_565 -> BitmapQuality.MEMORY_SAVING
                else -> BitmapQuality.STANDARD
            },
        )

        public override val bitmapConfig: Bitmap.Config
            get() = quality.toBitmapConfig()

        override fun make(): SkiaImageRegionDecoder = SkiaImageRegionDecoder(quality)
    }
}
