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
