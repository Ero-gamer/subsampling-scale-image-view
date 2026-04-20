package com.davemorrissey.labs.subscaleview.decoder

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.webkit.MimeTypeMap
import androidx.annotation.WorkerThread
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.internal.ERROR_FORMAT_NOT_SUPPORTED
import com.davemorrissey.labs.subscaleview.internal.URI_PATH_ASSET
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_CONTENT
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_FILE
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_RES
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_ZIP
import org.jetbrains.annotations.Blocking
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.Path

// ─── BitmapRegionDecoder factory wrappers ─────────────────────────────────────

@Blocking
internal fun BitmapRegionDecoder(
    pathName: String,
    context: Context?,
    uri: Uri?,
): BitmapRegionDecoder = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        BitmapRegionDecoder.newInstance(pathName)
    } else {
        @Suppress("DEPRECATION")
        BitmapRegionDecoder.newInstance(pathName, false)
    }
} catch (e: IOException) {
    if (e.message == ERROR_FORMAT_NOT_SUPPORTED) {
        throw ImageDecodeException.create(context, uri)
    } else {
        throw e
    }
}

@Blocking
internal fun BitmapRegionDecoder(
    inputStream: InputStream,
    context: Context?,
    uri: Uri?,
): BitmapRegionDecoder = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        BitmapRegionDecoder.newInstance(inputStream)
    } else {
        @Suppress("DEPRECATION")
        BitmapRegionDecoder.newInstance(inputStream, false)
    } ?: throw RuntimeException("Cannot instantiate BitmapRegionDecoder")
} catch (e: IOException) {
    if (e.message == ERROR_FORMAT_NOT_SUPPORTED) {
        throw ImageDecodeException.create(context, uri)
    } else {
        throw e
    }
}

// ─── Memory helpers ───────────────────────────────────────────────────────────

internal fun Context.isLowMemory(): Boolean {
    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
    return if (am != null) {
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.lowMemory
    } else {
        true
    }
}

// ─── Bitmap decode helpers ────────────────────────────────────────────────────

@WorkerThread
@Blocking
@SuppressLint("DiscouragedApi")
internal fun decodeResource(context: Context, uri: Uri, options: BitmapFactory.Options): Bitmap {
    val packageName = uri.authority
    val res = if (packageName == null || context.packageName == packageName) {
        context.resources
    } else {
        context.packageManager.getResourcesForApplication(packageName)
    }
    var id = 0
    val segments = uri.pathSegments
    val size = segments.size
    if (size == 2 && segments[0] == "drawable") {
        id = res.getIdentifier(segments[1], "drawable", packageName)
    } else if (size == 1 && TextUtils.isDigitsOnly(segments[0])) {
        try { id = segments[0].toInt() } catch (_: NumberFormatException) {}
    }
    return BitmapFactory.decodeResource(res, id, options)
}

@WorkerThread
@Blocking
internal fun ContentResolver.decodeBitmap(uri: Uri, options: BitmapFactory.Options): Bitmap? =
    openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }

@WorkerThread
@Blocking
internal fun AssetManager.decodeBitmap(name: String, options: BitmapFactory.Options): Bitmap? =
    open(name).use { BitmapFactory.decodeStream(it, null, options) }

// ─── ImageSource → Uri ───────────────────────────────────────────────────────

internal fun ImageSource.toUri(context: Context): Uri = when (this) {
    is ImageSource.Resource -> Uri.parse(
        ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.packageName + "/" + resourceId,
    )
    is ImageSource.Uri -> uri
    is ImageSource.Bitmap -> throw IllegalArgumentException("Bitmap source cannot be represented as Uri")
}

// ─── Image format detection ───────────────────────────────────────────────────

@Blocking
@Throws(Exception::class)
internal fun detectImageFormat(context: Context, uri: Uri): String? = when (uri.scheme) {
    URI_SCHEME_RES -> "resource"
    URI_SCHEME_ZIP -> uri.fragment?.let { entryName ->
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(entryName.substringAfterLast('.', ""))
            ?: ZipFile(uri.schemeSpecificPart).use { file ->
                val entry: ZipEntry? = file.getEntry(entryName)
                if (entry != null) {
                    file.getInputStream(entry).use { HttpURLConnection.guessContentTypeFromStream(it) }
                } else null
            }
    }
    URI_SCHEME_FILE -> {
        val path = uri.schemeSpecificPart
        if (path.startsWith(URI_PATH_ASSET, ignoreCase = true)) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                path.substring(URI_PATH_ASSET.length).substringAfterLast('.', ""),
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Files.probeContentType(Path(path))
        } else {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            opts.outMimeType
        }
    }
    URI_SCHEME_CONTENT -> context.contentResolver.getType(uri)
    else -> null
}

// ─── Chroma block alignment ───────────────────────────────────────────────────
//
// ROOT CAUSE of green/blue bars on large strip images (ALL formats, ALL Android versions):
//
// JPEG and WEBP lossy encode colour using the YCbCr colour space with 4:2:0 chroma
// subsampling. Luma (Y) is stored at full resolution, but chroma (Cb/Cr) is stored at
// half resolution (one chroma sample per 2×2 luma pixel block). This creates indivisible
// "minimum coding units" (MCUs):
//
//   • JPEG 4:2:0  →  16×16 px MCU (2×2 grid of 8×8 DCT blocks, one shared chroma block)
//   • JPEG 4:2:2  →  8×16 px MCU
//   • WEBP lossy  →  16×16 px macroblock (same YCbCr 4:2:0 structure)
//
// Android's BitmapRegionDecoder passes the requested Rect to Skia's JPEG/WEBP decoder,
// which must decide which MCUs to decode. If a tile boundary (left or top edge of the
// requested Rect) does NOT fall on an MCU boundary, the decoder reads chroma samples
// that belong to the ADJACENT MCU — producing a horizontal or vertical band of shifted
// colour (usually green, blue, or magenta tint).
//
// This is a Skia/Android platform bug present on EVERY Android version. It is especially
// visible on tall strip images (1400×10000+) where many tile boundaries exist, but will
// also appear on any image whose tile layout lands on a non-MCU-aligned row.
//
// ADDITIONAL COMPLICATION — inSampleSize > 1:
// When BitmapRegionDecoder decodes with inSampleSize=N, Android's decoder processes
// MCUs in groups of N×N. The effective alignment unit becomes N × MCU_SIZE. Using only
// the base MCU_SIZE (16) as the alignment when inSampleSize > 1 may leave a residual
// chroma shift because the decoder's internal subsampling arithmetic still rounds to
// the larger effective block boundary.
//
// THE FIX: Before calling decodeRegion(), expand all four edges of the requested Rect
// OUTWARD to the nearest effective-block boundary (CHROMA_BLOCK_SIZE × inSampleSize),
// clamped to the image dimensions. After decoding the slightly larger region, crop the
// bitmap back to the originally requested pixel area. Maximum expansion per edge:
// (CHROMA_BLOCK_SIZE × inSampleSize) − 1 pixels — negligible vs. typical tile sizes.
//
// If all four edges are already aligned, the fast path skips expand+crop entirely (zero
// overhead for aligned tiles, which is common for image widths that are multiples of 16).

/** Base chroma MCU size for JPEG 4:2:0 and WEBP lossy (16×16 px). */
internal const val CHROMA_BLOCK_SIZE = 16

/**
 * Computes the effective alignment block size for a given [inSampleSize].
 *
 * When decoding with [inSampleSize] > 1, BitmapRegionDecoder processes
 * [inSampleSize]×[inSampleSize] groups of pixels per MCU. The required alignment is
 * therefore [CHROMA_BLOCK_SIZE] × [inSampleSize], rounded up to the next power of 2
 * (since inSampleSize itself is always a power of 2 in SSIV).
 */
internal fun effectiveChromaBlockSize(inSampleSize: Int): Int {
    val s = inSampleSize.coerceAtLeast(1)
    return CHROMA_BLOCK_SIZE * s
}

/**
 * Returns true if this [Rect] is already aligned to [effectiveChromaBlockSize] on all
 * four edges (or the right/bottom edge exactly equals the image boundary). When true,
 * the caller can call [BitmapRegionDecoder.decodeRegion] directly with no alignment
 * expansion or post-decode crop — zero extra work.
 */
internal fun Rect.isChromaAligned(imageWidth: Int, imageHeight: Int, blockSize: Int): Boolean {
    val bs = blockSize.coerceAtLeast(1)
    return (left % bs == 0) &&
        (top % bs == 0) &&
        (right == imageWidth || right % bs == 0) &&
        (bottom == imageHeight || bottom % bs == 0)
}

/**
 * Returns a copy of this [Rect] with all four edges expanded OUTWARD to the nearest
 * [blockSize] boundary, clamped to [imageWidth] × [imageHeight].
 *
 * If no expansion is needed (all edges already aligned), the returned [Rect] has the
 * same coordinates as the receiver.
 */
internal fun Rect.alignToChromaBlocks(imageWidth: Int, imageHeight: Int, blockSize: Int): Rect {
    val bs = blockSize.coerceAtLeast(1)
    val alignedLeft = (left / bs) * bs
    val alignedTop = (top / bs) * bs
    val alignedRight = minOf(((right + bs - 1) / bs) * bs, imageWidth)
    val alignedBottom = minOf(((bottom + bs - 1) / bs) * bs, imageHeight)
    return Rect(alignedLeft, alignedTop, alignedRight, alignedBottom)
}

/**
 * After decoding an aligned region [aligned] instead of the originally requested [requested],
 * crops [rawBitmap] back to the pixels that correspond to [requested].
 *
 * [inSampleSize] is the downsampling factor used during the decode — coordinate offsets
 * must be divided by it to convert from source-image pixels to decoded-bitmap pixels.
 *
 * Returns [rawBitmap] unchanged (no new allocation) if no padding was added on any edge.
 * If a new bitmap is created, [rawBitmap] is recycled before returning.
 */
internal fun cropToRequestedRegion(
    rawBitmap: Bitmap,
    requested: Rect,
    aligned: Rect,
    inSampleSize: Int,
): Bitmap {
    val sample = inSampleSize.coerceAtLeast(1)

    // Pixel offset of the requested region within the decoded (aligned) bitmap.
    val cropLeft = (requested.left - aligned.left) / sample
    val cropTop = (requested.top - aligned.top) / sample

    // Expected output dimensions, rounding up to handle integer division in inSampleSize.
    val cropWidth = (requested.width() + sample - 1) / sample
    val cropHeight = (requested.height() + sample - 1) / sample

    // Clamp to actual decoded bitmap size (may be 1–2 px smaller at the image boundary).
    val safeWidth = cropWidth.coerceAtMost(rawBitmap.width - cropLeft).coerceAtLeast(1)
    val safeHeight = cropHeight.coerceAtMost(rawBitmap.height - cropTop).coerceAtLeast(1)

    // Fast path: decoded bitmap exactly matches the requested area.
    if (cropLeft == 0 && cropTop == 0 &&
        safeWidth == rawBitmap.width && safeHeight == rawBitmap.height
    ) {
        return rawBitmap
    }

    return try {
        val cropped = Bitmap.createBitmap(rawBitmap, cropLeft, cropTop, safeWidth, safeHeight)
        rawBitmap.recycle()
        cropped
    } catch (_: Exception) {
        // Safety net: if crop fails (edge case — invalid bounds at image boundary),
        // return the slightly larger raw bitmap rather than crashing. The 1–2 extra
        // border pixels are preferable to a crash.
        rawBitmap
    }
}
