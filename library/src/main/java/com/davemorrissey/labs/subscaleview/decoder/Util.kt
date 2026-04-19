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
// PROBLEM — green/blue horizontal bars on large images (ALL formats, not just WEBP):
//
// Android's BitmapRegionDecoder internally delegates to Skia, which decodes JPEG and
// WEBP using their respective entropy-coded block structures:
//
//   • JPEG 4:2:0:  8×8 luma (Y) blocks, 16×16 chroma (Cb/Cr) minimum coding units (MCU)
//   • JPEG 4:2:2:  8×8 luma (Y) blocks, 8×16 chroma MCUs
//   • WEBP lossy:  16×16 YCbCr macroblocks (always 4:2:0)
//   • PNG/WEBP-ll: lossless, no chroma subsampling → immune
//
// When a tile boundary (top or left edge of the requested Rect) does not fall on a
// 16-pixel boundary, BitmapRegionDecoder reads the Cb/Cr chroma planes starting from the
// wrong row — because chroma samples are stored at half-resolution and the decoder's
// integer arithmetic truncates instead of rounds. The misaligned chroma gives tiles a
// strong green or blue tint that persists until the decoder is recycled and re-opened.
//
// This affects EVERY ANDROID VERSION and every image format with 4:2:0 chroma.
// Users of 1400×10000+ strip images see it on almost every tile boundary because most
// strips have heights that are not multiples of 16.
//
// FIX: Align all four edges of the tile rect outward to multiples of CHROMA_BLOCK_SIZE
// before calling decodeRegion(). After decoding the slightly larger region, crop it back
// to the originally requested area. Maximum alignment padding: 15 pixels per edge,
// which is negligible compared to tile sizes of 256–512px.
//
// 16 is the correct alignment for both JPEG 4:2:0 and WEBP lossy. JPEG 4:2:2 MCUs are
// 8×16 but aligning to 16 in both dimensions covers that case as well.

internal const val CHROMA_BLOCK_SIZE = 16

/**
 * Returns a copy of this [Rect] with all four edges expanded outward to the nearest
 * [CHROMA_BLOCK_SIZE] boundary, clamped to [imageWidth] × [imageHeight].
 *
 * If no expansion is needed (all edges already aligned), the same rect values are
 * returned in a new [Rect] instance.
 */
internal fun Rect.alignToChromaBlocks(imageWidth: Int, imageHeight: Int): Rect {
    val alignedLeft = (left / CHROMA_BLOCK_SIZE) * CHROMA_BLOCK_SIZE
    val alignedTop = (top / CHROMA_BLOCK_SIZE) * CHROMA_BLOCK_SIZE
    val alignedRight = minOf(
        ((right + CHROMA_BLOCK_SIZE - 1) / CHROMA_BLOCK_SIZE) * CHROMA_BLOCK_SIZE,
        imageWidth,
    )
    val alignedBottom = minOf(
        ((bottom + CHROMA_BLOCK_SIZE - 1) / CHROMA_BLOCK_SIZE) * CHROMA_BLOCK_SIZE,
        imageHeight,
    )
    return Rect(alignedLeft, alignedTop, alignedRight, alignedBottom)
}

/**
 * Returns true if this [Rect] is already aligned to [CHROMA_BLOCK_SIZE] on all four
 * edges (or the right/bottom edge is at the image boundary). In this case no alignment
 * expansion or post-decode crop is needed and we can call decodeRegion() directly.
 */
internal fun Rect.isChromaAligned(imageWidth: Int, imageHeight: Int): Boolean =
    (left % CHROMA_BLOCK_SIZE == 0) &&
        (top % CHROMA_BLOCK_SIZE == 0) &&
        (right == imageWidth || right % CHROMA_BLOCK_SIZE == 0) &&
        (bottom == imageHeight || bottom % CHROMA_BLOCK_SIZE == 0)

/**
 * After decoding an aligned region [aligned] in place of the originally requested
 * [requested], crops the resulting [rawBitmap] back to exactly the requested pixels.
 *
 * [inSampleSize] is the downsampling factor used during decode — coordinate offsets
 * must be divided by it to convert from source-image pixels to decoded-bitmap pixels.
 *
 * Returns [rawBitmap] unchanged if no cropping is needed (no alignment padding was added).
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

    // Expected output size (round up to handle integer division in inSampleSize).
    val cropWidth = (requested.width() + sample - 1) / sample
    val cropHeight = (requested.height() + sample - 1) / sample

    // Clamp to actual decoded size (may be slightly smaller at image edges).
    val safeWidth = cropWidth.coerceAtMost(rawBitmap.width - cropLeft).coerceAtLeast(1)
    val safeHeight = cropHeight.coerceAtMost(rawBitmap.height - cropTop).coerceAtLeast(1)

    // If the decoded bitmap exactly matches the requested area, return it unchanged.
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
        // Safety net: if crop fails for any reason, return the raw bitmap as-is.
        // The slight color fringe is preferable to a crash.
        rawBitmap
    }
}
