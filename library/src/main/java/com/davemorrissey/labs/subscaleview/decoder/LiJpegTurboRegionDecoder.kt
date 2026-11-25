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
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import android.util.Log
import androidx.annotation.WorkerThread
import com.davemorrissey.labs.subscaleview.internal.URI_PATH_ASSET
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_CONTENT
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_FILE
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_RES
import com.davemorrissey.labs.subscaleview.internal.URI_SCHEME_ZIP
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipFile
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * [ImageRegionDecoder] backed by libjpeg-turbo for JPEG images and [BitmapRegionDecoder] (Skia) for
 * everything else (WebP, PNG, AVIF, ...), and for the rare JPEGs the native path cannot handle.
 *
 * The JPEG path never copies the image into the Java heap: the file (or the STORED entry of a CBZ) is
 * memory-mapped read-only and a tile decode only touches the pages of the MCU rows it needs. Deflated
 * CBZ entries and content:// images are first copied once to a small bounded cache directory.
 * Dimensions come from the JPEG header itself, so no [BitmapRegionDecoder] is created for JPEGs unless
 * a fallback is actually needed (sample size above 8, unsupported variants, native errors).
 *
 * JPEG detection uses the magic bytes (FF D8 FF), not the URI extension.
 */
public class LiJpegTurboRegionDecoder(
	private val quality: BitmapQuality = BitmapQuality.STANDARD,
) : ImageRegionDecoder {

	// Read lock: decoding. Write lock: init publishing results / recycle (waits for running decodes).
	private val lock = ReentrantReadWriteLock(true)
	private val skiaLock = Any()

	@Volatile
	private var handle = 0L

	@Volatile
	private var skia: BitmapRegionDecoder? = null

	/** Set when the Skia path serves a format that cannot decode a region cheaply (not a JPEG). */
	@Volatile
	private var costlyRegions = false

	@Volatile
	private var recycled = false

	// Consecutive native decode failures; after NATIVE_FAILURE_LIMIT the native path is skipped for this image.
	private val nativeFailures = AtomicInteger()

	private var appContext: Context? = null
	private var sourceUri: Uri? = null

	// Private copy of a content:// image (deleted on recycle). Cache-dir copies are shared and trimmed instead.
	private var privateTemp: File? = null

	@SuppressLint("DiscouragedApi")
	@WorkerThread
	override fun init(context: Context, uri: Uri): Point {
		val ctx = context.applicationContext ?: context
		var temp: File? = null
		var opened = 0L
		var size: IntArray? = null
		var progressive = false
		try {
			val source = if (nativeBroken) null else resolveNativeSource(ctx, uri)
			if (source != null) {
				temp = source.privateTemp
				opened = openNative(source)
				progressive = opened != 0L && isProgressiveJpeg(source.file, source.offset)
				size = if (opened != 0L) JpegNative.nativeInfo(opened) else null
				if (size == null && opened != 0L) {
					JpegNative.nativeClose(opened)
					opened = 0L
				}
			}
		} catch (_: Exception) {
			// Anything unexpected on the fast path: use the Skia path below.
			if (opened != 0L) JpegNative.nativeClose(opened)
			opened = 0L
			size = null
		} catch (e: LinkageError) {
			// Native library missing or unloadable (UnsatisfiedLinkError): never try it again, use Skia.
			Log.w("LiJpegTurbo", "Native JPEG path unavailable", e)
			nativeBroken = true
			opened = 0L
			size = null
		}

		if (opened != 0L && size != null) {
			lock.write {
				if (recycled) {
					JpegNative.nativeClose(opened)
					temp?.delete()
					error("Decoder recycled")
				}
				appContext = ctx
				sourceUri = uri
				privateTemp = temp
				handle = opened
				// A progressive JPEG has no independent rows: every region re-decodes the whole image.
				costlyRegions = progressive
			}
			return Point(size[0], size[1])
		}
		temp?.delete()
		val nonJpeg = !isLikelyJpeg(uri)

		val brd = openBitmapRegionDecoder(ctx, uri)
		lock.write {
			if (recycled) {
				brd.recycle()
				error("Decoder recycled")
			}
			appContext = ctx
			sourceUri = uri
			skia = brd
			costlyRegions = nonJpeg
		}
		return Point(brd.width, brd.height)
	}

	@WorkerThread
	override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
		val sample = sampleSize.coerceAtLeast(1)
		lock.read {
			check(!recycled) { "Cannot decode region - decoder recycled" }
			val h = handle
			if (h != 0L) {
				if (sample <= MAX_NATIVE_SAMPLE && nativeFailures.get() < NATIVE_FAILURE_LIMIT) {
					val start = System.nanoTime()
					val bmp = JpegNative.nativeDecodeRegion(
						h,
						sample,
						sRect.left,
						sRect.top,
						sRect.right,
						sRect.bottom,
						quality.toBitmapConfig() == Bitmap.Config.RGB_565,
					)
					if (bmp != null) {
						nativeFailures.set(0)
						DecoderStats.onNativeTile(System.nanoTime() - start)
						return bmp
					}
					nativeFailures.incrementAndGet()
				}
				DecoderStats.onSkiaFallback()
			}
			val start = System.nanoTime()
			val dec = skiaDecoder()
			val options = BitmapFactory.Options().apply {
				inSampleSize = sample
				inPreferredConfig = quality.toBitmapConfig()
			}
			val bmp = dec.decodeRegion(sRect, options) ?: error("BitmapRegionDecoder returned null for $sRect")
			DecoderStats.onSkiaTile(System.nanoTime() - start)
			return bmp
		}
	}

	override val isReady: Boolean
		get() = !recycled && (handle != 0L || skia?.isRecycled == false)

	override val isRegionDecodeCheap: Boolean get() = !costlyRegions

	override fun recycle() {
		lock.write {
			recycled = true
			val h = handle
			handle = 0L
			if (h != 0L) JpegNative.nativeClose(h)
			skia?.recycle()
			skia = null
			privateTemp?.delete()
			privateTemp = null
			appContext = null
			sourceUri = null
		}
	}

	/** Lazily created Skia decoder (JPEG fallback path). Called with the read lock held. */
	private fun skiaDecoder(): BitmapRegionDecoder = synchronized(skiaLock) {
		skia?.takeUnless { it.isRecycled }?.let { return it }
		val ctx = checkNotNull(appContext) { "Decoder is not initialised" }
		val uri = checkNotNull(sourceUri) { "Decoder is not initialised" }
		openBitmapRegionDecoder(ctx, uri).also { skia = it }
	}

	// -- Native source resolution ---------------------------------------------------------------

	private class NativeSource(val file: File, val offset: Long, val length: Long, val privateTemp: File? = null)

	/** Where the JPEG bytes can be mapped from, or null when this is not a (mappable) JPEG. */
	private fun resolveNativeSource(context: Context, uri: Uri): NativeSource? = when (uri.scheme) {
		URI_SCHEME_FILE -> {
			val path = uri.schemeSpecificPart
			val file = if (path.startsWith(URI_PATH_ASSET, ignoreCase = true)) null else File(path)
			if (file != null && file.isFile && startsWithJpegMagic(file)) NativeSource(file, 0, file.length()) else null
		}

		URI_SCHEME_ZIP -> resolveZipEntry(context, File(uri.schemeSpecificPart), uri.fragment)

		URI_SCHEME_CONTENT -> {
			val temp = newCacheFile(context, ".tmp")
			var ok = false
			try {
				val copied = context.contentResolver.openInputStream(uri)?.use { copyIfJpeg(it, temp) } ?: false
				ok = copied
				if (copied) NativeSource(temp, 0, temp.length(), privateTemp = temp) else null
			} finally {
				if (!ok) temp.delete()
			}
		}

		else -> null
	}

	private fun resolveZipEntry(context: Context, archive: File, name: String?): NativeSource? {
		if (name == null || !archive.isFile) return null
		ZipEntryLocator.locateStored(archive, name)?.let { loc ->
			if (hasJpegMagicAt(archive, loc[0])) return NativeSource(archive, loc[0], loc[1])
			return null
		}
		// Deflated (or ZIP64) entry: inflate once into the bounded cache directory, then map that file.
		val key = "${archive.path}|${archive.length()}|${archive.lastModified()}|$name"
		val cached = File(JpegExtractCache.dir(context), UUID.nameUUIDFromBytes(key.toByteArray()).toString() + ".jpg")
		if (cached.isFile && cached.length() > 0) {
			cached.setLastModified(System.currentTimeMillis())
			return NativeSource(cached, 0, cached.length())
		}
		return ZipFile(archive).use { zip ->
			val entry = zip.getEntry(name) ?: return@use null
			if (entry.size <= 0 || entry.size > MAX_CACHED_ENTRY_BYTES) return@use null
			val temp = newCacheFile(context, ".tmp")
			var ok = false
			try {
				val copied = zip.getInputStream(entry).use { copyIfJpeg(it, temp) }
				if (copied && temp.renameTo(cached)) {
					ok = true
					DecoderStats.onExtraction()
					NativeSource(cached, 0, cached.length())
				} else {
					null
				}
			} finally {
				if (!ok) temp.delete()
			}
		}
	}

	/** True when the JPEG at [offset] in [file] declares a progressive frame (SOF2/6/10/14). Header walk only. */
	private fun isProgressiveJpeg(file: File, offset: Long): Boolean = try {
		RandomAccessFile(file, "r").use { raf ->
			var pos = offset + 2 // after SOI
			var result = false
			var steps = 0
			val two = ByteArray(2)
			while (steps++ < MAX_MARKER_STEPS) {
				raf.seek(pos)
				val b = raf.read()
				if (b != 0xFF) break
				var m = raf.read()
				while (m == 0xFF) m = raf.read() // fill bytes
				if (m < 0 || m == 0xD9 || m == 0xDA) break
				if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
					result = m == 0xC2 || m == 0xC6 || m == 0xCA || m == 0xCE
					break
				}
				if (m in 0xD0..0xD8 || m == 0x01) {
					pos = raf.filePointer // standalone marker, no length
					continue
				}
				if (raf.read(two) != 2) break
				pos = raf.filePointer + (((two[0].toInt() and 0xFF) shl 8) or (two[1].toInt() and 0xFF)) - 2
			}
			result
		}
	} catch (_: IOException) {
		false
	}

	private fun openNative(source: NativeSource): Long = ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
		JpegNative.nativeOpen(pfd.fd, source.offset, source.length)
	}

	/** Copies [input] to [out] only when it starts with the JPEG magic. false: not a JPEG (nothing kept). */
	private fun copyIfJpeg(input: InputStream, out: File): Boolean {
		val magic = ByteArray(3)
		var n = 0
		while (n < 3) {
			val r = input.read(magic, n, 3 - n)
			if (r < 0) return false
			n += r
		}
		if (!isJpegMagic(magic)) return false
		trimCache(out.parentFile)
		out.outputStream().use { o ->
			o.write(magic)
			val buf = ByteArray(COPY_BUFFER_BYTES)
			var total = 3L
			while (true) {
				val r = input.read(buf)
				if (r < 0) break
				total += r
				if (total > MAX_CACHED_ENTRY_BYTES) return false
				o.write(buf, 0, r)
			}
		}
		return true
	}

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
					try {
						id = segments[0].toInt()
					} catch (_: NumberFormatException) {}
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

	private fun makeBrd(stream: InputStream, context: Context, uri: Uri): BitmapRegionDecoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
		BitmapRegionDecoder.newInstance(stream)
			?: throw ImageDecodeException.create(context, uri)
	} else {
		@Suppress("DEPRECATION")
		BitmapRegionDecoder.newInstance(stream, false)
			?: throw ImageDecodeException.create(context, uri)
	}

	// -- Factory ---------------------------------------------------------------------------------

	public class Factory @JvmOverloads constructor(
		private val quality: BitmapQuality = BitmapQuality.STANDARD,
	) : DecoderFactory<LiJpegTurboRegionDecoder> {
		// Required by DecoderFactory - applyBitmapConfig() in the app compares
		// this value to decide whether to reinstall the factory.
		override val bitmapConfig: Bitmap.Config
			get() = quality.toBitmapConfig()

		override fun make(): LiJpegTurboRegionDecoder = LiJpegTurboRegionDecoder(quality)
	}

	private companion object {
		/** libjpeg-turbo scales by 1/1, 1/2, 1/4 and 1/8 only; larger samples are decoded by Skia. */
		const val MAX_NATIVE_SAMPLE = 8
		private const val NATIVE_FAILURE_LIMIT = 3
		@Volatile
		var nativeBroken = false
		const val MAX_MARKER_STEPS = 64
		const val COPY_BUFFER_BYTES = 64 * 1024
		const val MAX_CACHED_ENTRY_BYTES = 64L * 1024 * 1024
		const val MAX_CACHE_FILES = 24
		const val MAX_CACHE_BYTES = 96L * 1024 * 1024
		const val STALE_AFTER_MS = 24L * 60 * 60 * 1000

		fun newCacheFile(context: Context, suffix: String): File = File(JpegExtractCache.dir(context), UUID.randomUUID().toString() + suffix)

		/** Cheap guess used only to pick the prefetch policy: file magic for plain files, the name otherwise. */
		fun isLikelyJpeg(uri: Uri): Boolean {
			if (uri.scheme == URI_SCHEME_FILE) {
				val file = File(uri.schemeSpecificPart)
				return file.isFile && startsWithJpegMagic(file)
			}
			if (uri.scheme == URI_SCHEME_CONTENT) return true // magic was checked while copying; keep prefetch
			val name = (uri.fragment ?: uri.lastPathSegment ?: return false).lowercase()
			return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".jpe")
		}

		fun isJpegMagic(b: ByteArray): Boolean = b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()

		fun startsWithJpegMagic(file: File): Boolean = hasJpegMagicAt(file, 0)

		fun hasJpegMagicAt(file: File, offset: Long): Boolean = try {
			java.io.RandomAccessFile(file, "r").use { raf ->
				val b = ByteArray(3)
				raf.seek(offset)
				raf.readFully(b)
				isJpegMagic(b)
			}
		} catch (_: IOException) {
			false
		}

		/**
		 * Keeps the extraction directory bounded: drops stale files, then the oldest ones until both the
		 * file-count and the size limit hold. Unlinking a file that is still mapped is safe on Linux.
		 */
		fun trimCache(dir: File?) {
			val files = dir?.listFiles() ?: return
			val now = System.currentTimeMillis()
			files.sortBy { it.lastModified() }
			var count = files.size
			var bytes = files.sumOf { it.length() }
			for (f in files) {
				// A young .tmp belongs to an extraction still in flight on another thread.
				if (f.name.endsWith(".tmp") && now - f.lastModified() < 5 * 60 * 1000L) continue
				val stale = now - f.lastModified() > STALE_AFTER_MS
				if (!stale && count < MAX_CACHE_FILES && bytes < MAX_CACHE_BYTES) break
				val len = f.length()
				if (f.delete()) {
					count--
					bytes -= len
				}
			}
		}
	}
}
