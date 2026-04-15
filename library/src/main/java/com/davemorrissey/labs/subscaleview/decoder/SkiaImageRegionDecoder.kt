package com.davemorrissey.labs.subscaleview.decoder

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.graphics.*
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
 * Default implementation of [com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder]
 * using Android's [android.graphics.BitmapRegionDecoder], based on the Skia library.
 *
 * Improvements over the original:
 * - Default bitmap config changed to [Bitmap.Config.ARGB_8888].
 *   The original defaulted to RGB_565. While RGB_565 uses half the memory, it
 *   produces visible colour banding on manga pages that contain gradients, skin tones,
 *   or subtle shading — exactly the content Kotatsu displays. ARGB_8888 produces
 *   correct colours and is the Android system default for BitmapRegionDecoder.
 *   Callers that explicitly need RGB_565 (e.g., thumbnail grids) can pass it via Factory.
 * - ReadWriteLock is used identically to the original for thread-safe concurrent reads.
 */
public class SkiaImageRegionDecoder @JvmOverloads constructor(
	// BUG 2 / QUALITY FIX: changed default from RGB_565 to ARGB_8888.
	// RGB_565 was the source of the "image looks de-sharpened / washed out" appearance
	// even on fresh loads — 16-bit colour depth discards the low bits of every channel,
	// producing visible banding on smooth gradients. ARGB_8888 is correct quality for
	// manga content.
	private val bitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888,
) : ImageRegionDecoder {

	private var decoder: BitmapRegionDecoder? = null
	private val decoderLock: ReadWriteLock = ReentrantReadWriteLock(true)

	@SuppressLint("DiscouragedApi")
	@Throws(Exception::class)
	@WorkerThread
	override fun init(context: Context, uri: Uri): Point {
		decoder = when (uri.scheme) {
			URI_SCHEME_RES -> {
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
					val resName = segments[1]
					id = res.getIdentifier(resName, "drawable", packageName)
				} else if (size == 1 && TextUtils.isDigitsOnly(segments[0])) {
					try {
						id = segments[0].toInt()
					} catch (ignored: NumberFormatException) {
					}
				}
				context.resources.openRawResource(id).use { BitmapRegionDecoder(it, context, uri) }
			}

			URI_SCHEME_ZIP -> ZipFile(uri.schemeSpecificPart).use { file ->
				val entry = requireNotNull(file.getEntry(uri.fragment)) {
					"Entry ${uri.fragment} not found in the zip"
				}
				file.getInputStream(entry).use { input ->
					BitmapRegionDecoder(input, context, uri)
				}
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
				val contentResolver = context.contentResolver
				contentResolver.openInputStream(uri)?.use { BitmapRegionDecoder(it, context, uri) }
					?: throw ImageDecodeException.create(context, uri)
			}

			else -> throw UnsupportedUriException(uri.toString())
		}
		return Point(decoder!!.width, decoder!!.height)
	}

	@WorkerThread
	override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
		decodeLock.lock()
		return try {
			check(decoder?.isRecycled == false) {
				"Cannot decode region after decoder has been recycled"
			}
			val options = BitmapFactory.Options()
			options.inSampleSize = sampleSize
			options.inPreferredConfig = bitmapConfig
			decoder?.decodeRegion(sRect, options)
				?: error("Skia image decoder returned null bitmap - image format may not be supported")
		} finally {
			decodeLock.unlock()
		}
	}

	@get:Synchronized
	override val isReady: Boolean
		get() = decoder?.isRecycled == false

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

	public class Factory @JvmOverloads constructor(
		// QUALITY FIX: default to ARGB_8888 for correct colour depth on manga tiles.
		// Pass RGB_565 explicitly only if you need aggressive memory savings and
		// accept visible colour banding (e.g., very low-end devices with <512 MB RAM).
		override val bitmapConfig: Bitmap.Config = Bitmap.Config.ARGB_8888,
	) : DecoderFactory<SkiaImageRegionDecoder> {

		override fun make(): SkiaImageRegionDecoder {
			return SkiaImageRegionDecoder(bitmapConfig)
		}
	}
}
