package com.davemorrissey.labs.subscaleview.decoder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.WorkerThread
import com.davemorrissey.labs.subscaleview.internal.URI_PATH_ASSET
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_CONTENT
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_FILE
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_RES
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_ZIP
import java.util.zip.ZipFile

/**
 * Default [ImageDecoder] using [BitmapFactory] (Skia). Used for non-tiled image loads.
 *
 * ### Why HARDWARE bitmaps are NOT used
 *
 * An earlier version of this library used [Bitmap.Config.HARDWARE] for full-image decodes
 * to skip the CPU→GPU upload on the first draw frame. This caused serious problems:
 *
 * 1. **Black tiles on zoom.** Hardware bitmaps cannot be drawn on a software
 *    [android.graphics.Canvas]. This is a silent failure: Android throws
 *    `IllegalStateException` which is caught by the coroutine error handler, the bitmap
 *    is never stored, and the tile renders as solid black. Several contexts cause software
 *    canvas usage: `WebtoonScalingFrame.setLayerType(LAYER_TYPE_SOFTWARE)` during pinch
 *    gestures, screenshots, and certain vendor-specific rendering paths.
 *
 * 2. **Pan restriction after zoom.** When the base-layer bitmap is a hardware bitmap and
 *    SSIV calls `Bitmap.createBitmap(source, x, y, w, h)` for internal cropping during
 *    downsampling changes, the operation throws. This leaves internal state inconsistent,
 *    causing `fitToBounds` to operate with wrong image dimensions and restrict panning.
 *
 * 3. **Pixel access blocked.** Edge detection (`EdgeDetector`) and crop bounds computation
 *    read pixel data via `Bitmap.getPixel()` / `copyPixelsToBuffer()`. Both throw
 *    `IllegalStateException` on hardware bitmaps.
 *
 * The correct config for all image types is [BitmapQuality.STANDARD] (ARGB_8888).
 * The GPU upload cost that HARDWARE was meant to avoid is typically < 10ms for manga-
 * sized images and is not perceptible to users.
 *
 * ### Color quality
 *
 * Default: [BitmapQuality.STANDARD] (ARGB_8888). Supports [BitmapQuality.MEMORY_SAVING]
 * (RGB_565) via the factory for low-RAM devices. RGBA_F16 ("HIGH") has been removed —
 * it provided no benefit on standard sRGB displays and doubled memory per tile.
 */
public class SkiaImageDecoder @JvmOverloads constructor(
    private val quality: BitmapQuality = BitmapQuality.STANDARD,
) : ImageDecoder {

    // ── Backwards-compat constructor ───────────────────────────────────────────
    @Deprecated("Use BitmapQuality constructor.")
    public constructor(bitmapConfig: Bitmap.Config) : this(
        quality = when (bitmapConfig) {
            Bitmap.Config.RGB_565 -> BitmapQuality.MEMORY_SAVING
            // RGBA_F16 (HIGH) is removed; fall back to STANDARD (ARGB_8888).
            else -> BitmapQuality.STANDARD
        },
    )

    @SuppressLint("DiscouragedApi")
    @Throws(Exception::class)
    @WorkerThread
    override fun decode(context: Context, uri: Uri, sampleSize: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            // Always use the resolved config — never HARDWARE (see class kdoc).
            inPreferredConfig = quality.toBitmapConfig()
        }

        val bitmap = when (uri.scheme) {
            URI_SCHEME_RES -> decodeResource(context, uri, options)

            URI_SCHEME_ZIP -> ZipFile(uri.schemeSpecificPart).use { file ->
                val entry = requireNotNull(file.getEntry(uri.fragment)) {
                    "Entry ${uri.fragment} not found in the zip"
                }
                file.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
            }

            URI_SCHEME_FILE -> {
                val path = uri.schemeSpecificPart
                if (path.startsWith(URI_PATH_ASSET, ignoreCase = true)) {
                    context.assets.decodeBitmap(path.substring(URI_PATH_ASSET.length), options)
                } else {
                    BitmapFactory.decodeFile(path, options)
                }
            }

            URI_SCHEME_CONTENT -> context.contentResolver.decodeBitmap(uri, options)

            else -> throw UnsupportedUriException(uri.toString())
        }

        return bitmap ?: throw ImageDecodeException.create(context, uri)
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    public class Factory @JvmOverloads constructor(
        public val quality: BitmapQuality = BitmapQuality.STANDARD,
    ) : DecoderFactory<SkiaImageDecoder> {

        @Deprecated("Use BitmapQuality constructor.")
        public constructor(bitmapConfig: Bitmap.Config) : this(
            quality = when (bitmapConfig) {
                Bitmap.Config.RGB_565 -> BitmapQuality.MEMORY_SAVING
                // RGBA_F16 (HIGH) is removed; fall back to STANDARD (ARGB_8888).
                else -> BitmapQuality.STANDARD
            },
        )

        override val bitmapConfig: Bitmap.Config
            get() = quality.toBitmapConfig()

        override fun make(): SkiaImageDecoder = SkiaImageDecoder(quality)
    }
}
