package com.davemorrissey.labs.subscaleview.decoder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import androidx.annotation.WorkerThread

/**
 * [ImageRegionDecoder] that wraps a base decoder (libjpeg-turbo or Skia) and applies the
 * [GpuTileRenderer] GPU shader pipeline to each decoded tile. This replaces [FilteringRegionDecoder]
 * (which did denoise/sharpen/vibrance/dither/grain in CPU pixel loops) with GPU fragment-shader
 * passes.
 *
 * **Tile-seam apron:** [GpuTileRenderer]'s multi-tap kernels (3x3 denoise, 5-tap RCAS, 25-tap
 * Adaptive-Sharpen) sample neighbouring pixels. Each tile is decoded independently, so without
 * this a kernel near a tile's edge would sample [android.opengl.GLES30.GL_CLAMP_TO_EDGE]
 * duplicates of its own border instead of the real image content just past it — a visible seam
 * at every tile boundary, worst on smooth colour gradients (webtoon/manhwa backgrounds) where a
 * 1-2px discontinuity is far more visible than on white manga paper. [decodeRegion] decodes
 * [APRON_PX] extra pixels of real image content on every side (clamped at the true image edges,
 * where [GLES30.GL_CLAMP_TO_EDGE] is in fact correct — there is no neighbour to sample), runs the
 * full filter chain over that padded region so every kernel sees genuine neighbouring pixels, and
 * then reads back only the originally-requested region (see [GpuTileRenderer.applyFilter]'s
 * cropRect — the GPU-side crop is a different [android.opengl.GLES30.glReadPixels] offset, not an
 * extra decode or an extra bitmap copy). [APRON_PX] is sized to the largest kernel radius
 * (Adaptive-Sharpen's 25-tap pattern reaches 3 decoded pixels) with one pixel of headroom.
 *
 * This does not fix seams in the separate zoomed-in bicubic *scaler* path
 * ([com.davemorrissey.labs.subscaleview.internal.BicubicRenderer] /
 * `com.davemorrissey.labs.subscaleview.internal.AgslBicubicScaler`), which draws multiple
 * already-decoded tile bitmaps as separate textures into one overlay and can still sample a
 * clamped edge near a shared tile boundary — a real, currently-unaddressed gap, tracked separately.
 *
 * **Screentone-aware sharpen capping:** manga screentone (periodic halftone dot patterns) can
 * alias into visible moiré under sharpening or a non-integer-ratio resample — smooth resampling
 * has no kernel-only fix for this, and destroying the pattern enough to avoid it would blur the
 * line art too (see the project's research notes). [ScreentonePeriodicityDetector] runs an
 * autocorrelation-based periodicity check on one cheap downsampled thumbnail per image (not per
 * tile), once, in [init] — negligible cost next to a single full-resolution tile decode — and
 * the result CAPS (never disables) [renderer]'s effective RCAS/Adaptive-Sharpen intensity for
 * every tile of that image. Smooth colour art and photographic content score far below the
 * detection threshold and are unaffected (see that file's doc for the verification notes).
 *
 * **What this does NOT replace:** Brightness, Contrast, and Saturation remain as CPU/Canvas
 * `ColorMatrix` paint filters on SSIV — they are cheap, allocation-free, and zero latency.
 *
 * **Shader controls** (set via [Factory]'s constructor — the app's `ReaderSettings.applyBitmapConfig()`
 * is the single call-site that constructs this). Every filter is an independent
 * (enable, intensity) pair; they stack and none excludes another. (Bicubic *scalers* are not
 * decode-time filters — see [com.davemorrissey.labs.subscaleview.ImageScaler].)
 * - Deband     → f3kdb/flash3kyuu_deband's documented "square" mode (independent implementation)
 * - Denoise    → real bilateral filter (Tomasi & Manduchi 1998), 3x3
 * - Darken     → line darkening (Anime4K-inspired heuristic, not the Anime4K algorithm)
 * - Vibrance   → real SweetFX/CeeJay.dk Vibrance
 * - RCAS       → real AMD FidelityFX FSR1 RCAS
 * - Adaptive-Sharpen → real bacondither Adaptive-Sharpen (one filter, two shader passes)
 *
 * If the GPU renderer fails to initialise (unsupported driver, OOM), tile decoding falls back
 * to returning the unfiltered bitmap — the image remains fully readable.
 */
public class GpuFilteringDecoder(
	private val inner: ImageRegionDecoder,
	public val renderer: GpuTileRenderer,
) : ImageRegionDecoder {
	// Set by init(); needed to clamp the apron at the true image edges (see decodeRegion), where
	// GL_CLAMP_TO_EDGE is in fact correct since there is no further neighbour to sample.
	@Volatile private var imageWidth = 0

	@Volatile private var imageHeight = 0

	// Set by init() when sharpening is enabled; see ScreentonePeriodicityDetector. 1f (no
	// reduction) whenever gating is off, sharpening is off, or the thumbnail analysis fails —
	// this must never be able to strand tiles unfiltered by throwing.
	@Volatile private var sharpenCap = 1f

	override fun init(
		context: Context,
		uri: Uri,
	): Point =
		inner.init(context, uri).also {
			imageWidth = it.x
			imageHeight = it.y
			if (renderer.enableScreentoneCap && (renderer.enableRcas || renderer.enableAdaptiveSharpen)) {
				sharpenCap =
					try {
						computeSharpenCap(it.x, it.y)
					} catch (e: Throwable) {
						1f // analysis failing must never block sharpening from working at all
					}
			}
		}

	/** Downsamples the WHOLE image to roughly [THUMBNAIL_TARGET_PX] on its shorter side (one
	 *  cheap decode, negligible next to a single full-resolution tile) and scores it for
	 *  screentone periodicity. */
	private fun computeSharpenCap(
		imgW: Int,
		imgH: Int,
	): Float {
		if (imgW <= 0 || imgH <= 0) return 1f
		val shortSide = minOf(imgW, imgH)
		val sampleSize = Integer.highestOneBit(maxOf(1, shortSide / THUMBNAIL_TARGET_PX))
		val thumb = inner.decodeRegion(Rect(0, 0, imgW, imgH), sampleSize)
		return try {
			val score = ScreentonePeriodicityDetector.detectPeriodicityScore(thumb)
			ScreentonePeriodicityDetector.scoreToSharpenCap(score)
		} finally {
			thumb.recycle()
		}
	}

	// The interface provides a default impl for init(context, ImageSource) that delegates to
	// init(context, uri) via source.toUri(context). No override needed here — the default
	// impl will call our init(context, uri) above correctly.

	@WorkerThread
	override fun decodeRegion(
		sRect: Rect,
		sampleSize: Int,
	): Bitmap {
		// Apron in SOURCE pixels: APRON_PX decoded pixels of real neighbouring content on each
		// side, scaled up by sampleSize since sRect is in full-resolution source coordinates but
		// the apron budget (kernel radius) is in already-downsampled decoded-pixel units.
		val apronSrc = APRON_PX * sampleSize
		val imgW = imageWidth
		val imgH = imageHeight
		val padded =
			if (imgW > 0 && imgH > 0) {
				Rect(
					(sRect.left - apronSrc).coerceAtLeast(0),
					(sRect.top - apronSrc).coerceAtLeast(0),
					(sRect.right + apronSrc).coerceAtMost(imgW),
					(sRect.bottom + apronSrc).coerceAtMost(imgH),
				)
			} else {
				// init() hasn't run (shouldn't happen — SSIV always inits before decoding) or
				// reported a degenerate size: fall back to the exact requested region, unpadded.
				sRect
			}

		val tile = inner.decodeRegion(padded, sampleSize)
		return try {
			// Where within the padded/decoded tile the originally-requested region actually
			// landed. Decoders round width/height division by sampleSize independently per
			// side, so this is derived from the padded rect's own decoded size, not by assuming
			// a fixed ratio.
			val cropRect = if (padded == sRect) null else cropWithin(tile, padded, sRect)
			val filtered = renderer.applyFilter(tile, cropRect, sharpenCap)
			// Renderer returned a new bitmap — recycle the intermediate padded-tile allocation.
			if (filtered !== tile) tile.recycle()
			filtered
		} catch (e: Throwable) {
			// Never let a GPU error strand a tile. Fall back to an unfiltered, correctly-cropped
			// bitmap so the image stays readable (cropping matters here too: `tile` includes the
			// apron, and returning it uncropped would misalign every tile in the grid).
			if (padded == sRect) {
				tile
			} else {
				val c = cropWithin(tile, padded, sRect)
				val cropped = Bitmap.createBitmap(tile, c.left, c.top, c.width(), c.height())
				// createBitmap returns its source when the crop is the whole bitmap: never recycle that.
				if (cropped !== tile) tile.recycle()
				cropped
			}
		}
	}

	/**
	 * Where [sRect] landed inside the decoded padded [tile]. Offsets and size are rounded from the
	 * padded rect's own decoded/source ratio (decoders round per side when sampleSize > 1), then
	 * clamped so the result is always non-empty and inside the tile.
	 */
	private fun cropWithin(
		tile: Bitmap,
		padded: Rect,
		sRect: Rect,
	): Rect {
		val sx = tile.width.toFloat() / padded.width()
		val sy = tile.height.toFloat() / padded.height()
		val left = Math.round((sRect.left - padded.left) * sx).coerceIn(0, tile.width - 1)
		val top = Math.round((sRect.top - padded.top) * sy).coerceIn(0, tile.height - 1)
		val right = (left + Math.round(sRect.width() * sx)).coerceIn(left + 1, tile.width)
		val bottom = (top + Math.round(sRect.height() * sy)).coerceIn(top + 1, tile.height)
		return Rect(left, top, right, bottom)
	}

	override val isReady: Boolean get() = inner.isReady

	override fun recycle() {
		inner.recycle()
		// Do NOT call renderer.release() here — the renderer is shared across multiple
		// decoder instances (one per tile worker thread) for the same SSIV image load.
		// It is released by the Factory when the factory itself is replaced (in applyBitmapConfig).
	}

	private companion object {
		// Largest kernel radius among the filter chain, in already-decoded pixels: the
		// Adaptive-Sharpen edge pass's 25-tap pattern reaches offsets up to magnitude 3
		// (see adaptive_edge.frag), so 3 would be exactly enough; +1 for headroom against any
		// future kernel and to absorb the sub-pixel rounding a sampleSize division can introduce.
		const val APRON_PX = 4

		// Target size (px, shorter side) for the one-shot screentone-periodicity thumbnail —
		// small enough to be essentially free next to a full-resolution tile decode, large
		// enough that realistic screentone dot pitches (verified 3-20px at this scale) still
		// fall within the detector's search radius.
		const val THUMBNAIL_TARGET_PX = 128
	}

	// ── Factory ───────────────────────────────────────────────────────────────

	public class Factory(
		private val innerFactory: DecoderFactory<out ImageRegionDecoder>,
		enableDeband: Boolean = false,
		debandIntensity: Float = 0f,
		enableDenoise: Boolean = false,
		enableDarken: Boolean = false,
		enableVibrance: Boolean = false,
		denoiseStrength: Float = 0.5f,
		vibranceIntensity: Float = 1f,
		enableRcas: Boolean = false,
		rcasIntensity: Float = 0f,
		enableAdaptiveSharpen: Boolean = false,
		adaptiveSharpenIntensity: Float = 0f,
		enableScreentoneCap: Boolean = true,
		// The renderer is shared across all decoder instances produced by this factory so the
		// EGL context is created once per SSIV image load, not once per tile decode worker.
		// Public (not internal): app code in a separate module reads/reuses this renderer
		// (see ReaderSettings.applyBitmapConfig).
		public val renderer: GpuTileRenderer,
	) : DecoderFactory<GpuFilteringDecoder> {
		init {
			renderer.enableDeband = enableDeband
			renderer.debandIntensity = debandIntensity
			renderer.enableDenoise = enableDenoise
			renderer.enableDarken = enableDarken
			renderer.enableVibrance = enableVibrance
			renderer.denoiseStrength = denoiseStrength
			renderer.vibranceIntensity = vibranceIntensity
			renderer.enableRcas = enableRcas
			renderer.rcasIntensity = rcasIntensity
			renderer.enableAdaptiveSharpen = enableAdaptiveSharpen
			renderer.adaptiveSharpenIntensity = adaptiveSharpenIntensity
			renderer.enableScreentoneCap = enableScreentoneCap
		}

		override val bitmapConfig: Bitmap.Config? get() = innerFactory.bitmapConfig

		override fun make(): GpuFilteringDecoder = GpuFilteringDecoder(innerFactory.make(), renderer)
	}
}
