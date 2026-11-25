package com.davemorrissey.labs.subscaleview.decoder

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumSet
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Off-screen EGL + GLES 3.0 renderer that applies a chain of fragment-shader passes to every
 * decoded tile Bitmap, replacing the removed CPU filter loops.
 *
 * ### Pipeline (only enabled stages run; each stage's output feeds the next)
 * 0. **Deband** (`deband.frag`) — f3kdb/flash3kyuu_deband's own documented "square" mode
 *    (sample_mode=2, blur_first=true, both that project's own defaults), independently
 *    implemented from its published documentation (see that file's doc comment on why this is
 *    not GPL-encumbered). Runs first, before sharpening could re-emphasise a still-visible band.
 * 1. **Pre** (`manga_enhance.frag`) — denoise: real bilateral filter (Tomasi & Manduchi 1998,
 *    see that file for the derivation/verification notes); line darken: still a homemade
 *    Anime4K-inspired heuristic (Anime4K's own DarkLines shader is itself an admittedly
 *    ambiguous, undocumented heuristic — porting it wouldn't add real rigor over what's here).
 * 2. **RCAS** (`rcas.frag`) — real AMD FidelityFX FSR1 RCAS, single pass.
 * 3. **Adaptive-Sharpen** (`adaptive_edge.frag` then `adaptive_sharpen.frag`) — real
 *    bacondither Adaptive-Sharpen, two passes: the first computes a per-pixel edge strength
 *    into an RGBA16F texture (`rgb` = color, `a` = edge), the second reads that 25-tap
 *    neighbourhood to sharpen. Needs its own float intermediate because edge values exceed 1.0.
 * 4. **Post** (`manga_enhance.frag`, vibrance only) — real SweetFX/CeeJay.dk Vibrance.
 *
 * RCAS and Adaptive-Sharpen are independent (enable, intensity) pairs like every other filter
 * here — neither excludes the other. If both are enabled, RCAS runs first and Adaptive-Sharpen
 * sharpens its output; this ordering is arbitrary (the two rarely make sense together) but
 * deterministic.
 *
 * ### Why EGL pbuffer instead of TextureView?
 * TextureView requires a live window surface and choreographer-driven render thread —
 * unsuitable for background tile post-processing. An EGL pbuffer is purely off-screen:
 * no display dependency, integrates with SSIV's Bitmap→Canvas tile pipeline without
 * touching pan/zoom or gesture logic, and avoids SurfaceTexture latency.
 *
 * ### Thread safety
 * A single [ReentrantLock] serialises all GL calls.
 *
 * ### Lifecycle
 * Call [release] when the containing [GpuFilteringDecoder] is recycled.
 *
 * ### Visibility
 * Public (not `internal`) because app code that embeds SSIV constructs and configures
 * this class directly (see [GpuFilteringDecoder.Factory] and [ReaderSettings] in the
 * consuming app), which lives in a separate Gradle module from this library.
 */
public class GpuTileRenderer(
	private val context: Context,
) {
	// ── EGL state ─────────────────────────────────────────────────────────────

	private val lock = ReentrantLock()

	private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
	private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
	private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

	// Cached config — avoids repeated eglChooseConfig on every pbuffer resize.
	private var cachedConfig: EGLConfig? = null

	/** True only between a successful eglInitialize and the matching eglTerminate (libEGL refcounts them). */
	private var eglInited = false

	// GL_MAX_TEXTURE_SIZE of this device; images larger than this are returned unfiltered.
	private var maxTextureSize = Int.MAX_VALUE

	// Set by release(); a released renderer never re-initialises itself (in-flight decodes
	// that still hold a reference would otherwise leak a fresh EGL context).
	@Volatile private var isReleased = false

	/** Trim requested while a tile was rendering (TRIM_*), consumed under [lock] by [applyPendingTrim]. */
	@Volatile private var pendingTrim = TRIM_NONE

	private var quadVbo = 0
	private var quadVao = 0

	// ── Shader programs ──────────────────────────────────────────────────────
	private var progEnhance = 0 // manga_enhance.frag — denoise/darken (pre) or vibrance (post)
	private var progDeband = 0 // deband.frag
	private var progRcas = 0 // rcas.frag
	private var progEdge = 0 // adaptive_edge.frag
	private var progSharpen = 0 // adaptive_sharpen.frag
	private var progBinarize = 0 // binarize.frag

	private class EnhanceUniforms(
		program: Int,
	) {
		val uTexture = GLES30.glGetUniformLocation(program, "u_texture")
		val uTexelSize = GLES30.glGetUniformLocation(program, "u_texelSize")
		val uDenoise = GLES30.glGetUniformLocation(program, "u_enableDenoise")
		val uDarken = GLES30.glGetUniformLocation(program, "u_enableDarken")
		val uVibrance = GLES30.glGetUniformLocation(program, "u_enableVibrance")
		val uDenoiseStrength = GLES30.glGetUniformLocation(program, "u_denoiseStrength")
		val uVibranceIntensity = GLES30.glGetUniformLocation(program, "u_vibranceIntensity")
	}

	private class DebandUniforms(
		program: Int,
	) {
		val uTexture = GLES30.glGetUniformLocation(program, "u_texture")
		val uTexelSize = GLES30.glGetUniformLocation(program, "u_texelSize")
		val uRange = GLES30.glGetUniformLocation(program, "u_range")
		val uThreshold = GLES30.glGetUniformLocation(program, "u_threshold")
		val uGrain = GLES30.glGetUniformLocation(program, "u_grain")
		val uOrigin = GLES30.glGetUniformLocation(program, "u_origin")
		val uEnableVibrance = GLES30.glGetUniformLocation(program, "u_enableVibrance")
		val uVibranceIntensity = GLES30.glGetUniformLocation(program, "u_vibranceIntensity")
	}

	private class RcasUniforms(
		program: Int,
	) {
		val uTexture = GLES30.glGetUniformLocation(program, "u_texture")
		val uTexelSize = GLES30.glGetUniformLocation(program, "u_texelSize")
		val uRcasCon = GLES30.glGetUniformLocation(program, "u_rcasCon")
		val uEnableVibrance = GLES30.glGetUniformLocation(program, "u_enableVibrance")
		val uVibranceIntensity = GLES30.glGetUniformLocation(program, "u_vibranceIntensity")
	}

	private class EdgeUniforms(
		program: Int,
	) {
		val uTexture = GLES30.glGetUniformLocation(program, "u_texture")
		val uTexelSize = GLES30.glGetUniformLocation(program, "u_texelSize")
	}

	private class SharpenUniforms(
		program: Int,
	) {
		val uTexture = GLES30.glGetUniformLocation(program, "u_texture")
		val uTexelSize = GLES30.glGetUniformLocation(program, "u_texelSize")
		val uCurveHeight = GLES30.glGetUniformLocation(program, "u_curveHeight")
		val uEnableVibrance = GLES30.glGetUniformLocation(program, "u_enableVibrance")
		val uVibranceIntensity = GLES30.glGetUniformLocation(program, "u_vibranceIntensity")
	}

	private class BinarizeUniforms(
		program: Int,
	) {
		val uTexture = GLES30.glGetUniformLocation(program, "u_texture")
		val uTexelSize = GLES30.glGetUniformLocation(program, "u_texelSize")
		val uStrength = GLES30.glGetUniformLocation(program, "u_strength")
		val uRadius = GLES30.glGetUniformLocation(program, "u_radius")
	}

	private var binarizeU: BinarizeUniforms? = null
	private var enhanceU: EnhanceUniforms? = null
	private var debandU: DebandUniforms? = null
	private var rcasU: RcasUniforms? = null
	private var edgeU: EdgeUniforms? = null
	private var sharpenU: SharpenUniforms? = null

	/** A reusable offscreen color attachment. Recreated only when the requested size changes. */
	private class FboTex(
		private val internalFormat: Int,
		private val type: Int,
	) {
		var texture = 0
		var fbo = 0
		var width = 0
		var height = 0

		fun ensureSize(
			w: Int,
			h: Int,
		) {
			if (texture != 0 && width == w && height == h) return
			release()
			val texIds = IntArray(1)
			GLES30.glGenTextures(1, texIds, 0)
			texture = texIds[0]
			GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
			GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
			GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
			GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
			GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
			GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internalFormat, w, h, 0, GLES30.GL_RGBA, type, null)

			val fboIds = IntArray(1)
			GLES30.glGenFramebuffers(1, fboIds, 0)
			fbo = fboIds[0]
			GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
			GLES30.glFramebufferTexture2D(
				GLES30.GL_FRAMEBUFFER,
				GLES30.GL_COLOR_ATTACHMENT0,
				GLES30.GL_TEXTURE_2D,
				texture,
				0,
			)
			check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
				"Intermediate FBO incomplete (${w}x$h, format=0x${internalFormat.toString(16)})"
			}
			width = w
			height = h
		}

		fun release() {
			if (texture != 0) {
				GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
				texture = 0
			}
			if (fbo != 0) {
				GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
				fbo = 0
			}
			width = 0
			height = 0
		}
	}

	// Two RGBA8 ping-pong buffers, reused for every RGBA8-producing stage (pre-enhance, RCAS,
	// adaptive-sharpen's second pass, post-enhance).
	private val pingA = FboTex(GLES30.GL_RGBA8, GLES30.GL_UNSIGNED_BYTE)
	private val pingB = FboTex(GLES30.GL_RGBA8, GLES30.GL_UNSIGNED_BYTE)

	// RGBA16F buffer for the adaptive-sharpen edge pass (edge values exceed 1.0; needs float).
	private val edgeBuf = FboTex(GLES30.GL_RGBA16F, GLES30.GL_HALF_FLOAT)

	// Every filter is an independent (enable, intensity) pair — none is mutually exclusive.
	@Volatile var enableDenoise = false

	@Volatile var enableDarken = false

	@Volatile var enableVibrance = false

	/** f3kdb-style deband (see deband.frag's doc comment). Runs FIRST, before every other filter,
	 *  on the theory that sharpening a still-visible band edge would only make it more visible. */
	@Volatile var enableDeband = false

	@Volatile var debandIntensity = 0f

	/** Real bilateral filter's range-Gaussian sigma_r, 0.0 (mild, edge-preserving) .. 1.0
	 *  (aggressive). Only used when [enableDenoise]. See manga_enhance.frag's doc comment. */
	@Volatile var denoiseStrength = 0.5f

	/** Vibrance boost magnitude, 0.0 (no-op) .. 1.0 (full). Only used when [enableVibrance]. */
	@Volatile var vibranceIntensity = 1f

	/** Sharpen: real AMD FidelityFX RCAS. */
	@Volatile var enableRcas = false

	@Volatile var rcasIntensity = 0f

	/** Sharpen: real bacondither Adaptive-Sharpen. */
	@Volatile var enableAdaptiveSharpen = false

	/** Lets [GpuFilteringDecoder] reduce sharpening on detected screentone pages (see [ScreentonePeriodicityDetector]). */
	@Volatile var enableScreentoneCap = true

	/** Set once at init: whether RGBA16F color attachments are renderable (see [detectFloatTargets]). */
	@Volatile private var floatTargets = false

	@Volatile var adaptiveSharpenIntensity = 0f

	/** Local-threshold black & white (binarize.frag). Runs LAST, after sharpening and vibrance. */
	@Volatile var enableBinarize = false

	/** Mix between the original colour (0) and pure black & white (1). Only used when [enableBinarize]. */
	@Volatile var binarizeStrength = 1f

	/** Which body stage — if any — is the pipeline's actual last one for a given call, i.e. the
	 *  one vibrance (purely pointwise, so freely fusable into any stage's own draw call) should
	 *  be folded into rather than getting a whole separate pass to itself. See applyFilterLocked. */
	private enum class Stage { NONE, DEBAND, PRE_ENHANCE, RCAS, ADAPTIVE_SHARPEN }

	// A stage whose parameters make it an exact identity is not "active": no GL work, no readback, no copy.
	private fun debandActive(): Boolean = enableDeband && debandIntensity > 0f

	private fun vibranceActive(): Boolean = enableVibrance && vibranceIntensity != 0f

	private fun hasActiveFilter(): Boolean =
		debandActive() || enableDenoise || enableDarken || vibranceActive() ||
			(enableRcas && rcasIntensity > 0f) || enableAdaptiveSharpen || enableBinarize

	/**
	 * Tile-seam apron in decoded pixels: the summed kernel reach of the ENABLED stages (they chain, so each stage
	 * reads pixels the previous one could only compute with its own apron). Deband <= 4, denoise/darken 1, RCAS 1,
	 * Adaptive-Sharpen 5 (edge pass 2 + sharpen pass 3), binarize [BINARIZE_RADIUS_PX].
	 */
	fun requiredApronPx(): Int {
		var r = 0
		if (debandActive()) r += 4
		if (enableDenoise || enableDarken) r += 1
		if (enableAdaptiveSharpen) r += 5 else if (enableRcas && rcasIntensity > 0f) r += 1
		if (enableBinarize) r += BINARIZE_RADIUS_PX.toInt()
		return r.coerceAtLeast(1)
	}

	private var ready = false

	// After a failed init (or repeated render errors) tiles return unfiltered until this uptime, instead of redoing
	// EGL setup under the lock for every tile; a later attempt may then recover from a transient failure.
	@Volatile private var retryAtMs = 0L

	private var consecutiveErrors = 0

	private enum class ShaderId(val file: String) {
		ENHANCE("manga_enhance.frag"),
		DEBAND("deband.frag"),
		RCAS("rcas.frag"),
		EDGE("adaptive_edge.frag"),
		SHARPEN("adaptive_sharpen.frag"),
		BINARIZE("binarize.frag"),
	}

	// Programs whose compile/link failed on this GPU (persisted for a week, see loadPersistedFailures). Each is
	// built lazily on first use, so one bad shader disables only its own stage.
	private val failedProgs = EnumSet.noneOf(ShaderId::class.java)

	private var gpuKey = ""

	@Volatile private var lastUseMs = 0L

	private val gcScheduled = AtomicBoolean(false)

	// Registered while the renderer is live (see initLocked / release): frees GPU memory under pressure or when
	// the app goes to the background.
	private var callbacksRegistered = false

	private val trimCallbacks = object : ComponentCallbacks2 {
		override fun onTrimMemory(level: Int) {
			if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
				trimMemory(full = level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
			}
		}

		override fun onConfigurationChanged(newConfig: Configuration) = Unit

		@Deprecated("Deprecated in Java")
		override fun onLowMemory() = trimMemory(full = true)
	}

	private val gcExecutor: ScheduledThreadPoolExecutor by lazy {
		ScheduledThreadPoolExecutor(1, ThreadFactory { r -> Thread(r, "ssiv-gpu-gc").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } })
			.apply {
				setKeepAliveTime(5, TimeUnit.SECONDS)
				allowCoreThreadTimeOut(true)
			}
	}

	// Readback staging buffer reused across tiles (only touched under [lock]; dropped on release).
	private var readBuf: ByteBuffer? = null

	// ── Public API ────────────────────────────────────────────────────────────

	fun init(): Boolean =
		lock.withLock {
			isReleased = false
			retryAtMs = 0L
			consecutiveErrors = 0
			initLocked()
		}

	/**
	 * Applies the enabled filter chain to [bitmap]. If [cropRect] is given, only that
	 * sub-rectangle of the FILTERED result is returned (as a bitmap of exactly [cropRect]'s
	 * size) — the rest of [bitmap] is still decoded, uploaded and pushed through every shader
	 * pass (so multi-tap kernels near [cropRect]'s edges see real neighbouring pixels instead of
	 * [android.opengl.GLES30.GL_CLAMP_TO_EDGE] duplicating the tile's own border), it is simply
	 * never read back. This is how [GpuFilteringDecoder] fixes the tile-seam artefact multi-tap
	 * kernels would otherwise show at every tile boundary: the caller decodes a small overlap
	 * apron around the requested region and passes the original region back as [cropRect] — the
	 * GPU-side crop costs nothing extra (same [android.opengl.GLES30.glReadPixels] call, a
	 * different offset/size) rather than decoding+filtering the full padded tile and copying a
	 * sub-rectangle out of a second [Bitmap] afterwards.
	 *
	 * @param sharpenCap Multiplier in `[0,1]` applied to [rcasIntensity]/[adaptiveSharpenIntensity]
	 *   for THIS call only (never mutates the shared fields — the renderer is shared across
	 *   concurrent decoder instances for different images, see [GpuFilteringDecoder]'s doc, so a
	 *   per-image value must be a call parameter, not shared state). 1.0 (the default) means no
	 *   reduction. See [ScreentonePeriodicityDetector] for where a caller gets this value from.
	 */
	fun applyFilter(
		bitmap: Bitmap,
		cropRect: Rect? = null,
		sharpenCap: Float = 1f,
		originX: Int = 0,
		originY: Int = 0,
	): Bitmap {
		if (isReleased) return unfiltered(bitmap, cropRect)
		if (!hasActiveFilter()) return unfiltered(bitmap, cropRect)
		if (SystemClock.uptimeMillis() < retryAtMs || shouldSkip(bitmap)) return unfiltered(bitmap, cropRect)
		val result = lock.withLock {
			val r = filterLocked(bitmap, cropRect, sharpenCap, originX, originY)
			applyPendingTrim()
			r
		}
		scheduleGc()
		return keepSourceConfig(bitmap, result)
	}

	/**
	 * The GPU read-back is ARGB_8888. A tile that came in as RGB_565 (memory saver, opaque by definition) is
	 * handed back as RGB_565, so a filtered tile does not cost twice the memory of an unfiltered one. Not
	 * with deband active: its grain would be quantised away. Runs outside the GL lock (plain CPU copy).
	 */
	private fun keepSourceConfig(source: Bitmap, result: Bitmap): Bitmap {
		if (result === source || source.config != Bitmap.Config.RGB_565 || result.config != Bitmap.Config.ARGB_8888 ||
			result.isRecycled || debandActive()
		) {
			return result
		}
		return try {
			val small = result.copy(Bitmap.Config.RGB_565, false)
			if (small != null) {
				result.recycle()
				small
			} else {
				result
			}
		} catch (_: OutOfMemoryError) {
			result
		}
	}

	private fun filterLocked(
		bitmap: Bitmap,
		cropRect: Rect?,
		sharpenCap: Float,
		originX: Int,
		originY: Int,
	): Bitmap {
		if (isReleased) return unfiltered(bitmap, cropRect)
		if (!ready && (SystemClock.uptimeMillis() < retryAtMs || !initLocked())) return unfiltered(bitmap, cropRect)
		return try {
			applyFilterLocked(bitmap, cropRect, sharpenCap.coerceIn(0f, 1f), originX, originY).also {
				consecutiveErrors = 0
				lastUseMs = SystemClock.uptimeMillis()
			}
		} catch (e: IllegalArgumentException) {
			throw e
		} catch (e: RuntimeException) {
			// GL state may be bad (lost context, driver error): tear down, re-init on the next tile, and
			// give up for a while after repeated failures. The tile itself is returned unfiltered.
			Log.e(TAG, "GPU filter failed — tile returned unfiltered", e)
			releaseLocked()
			if (++consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
				consecutiveErrors = 0
				retryAtMs = SystemClock.uptimeMillis() + RETRY_COOLDOWN_MS
			}
			unfiltered(bitmap, cropRect)
		} catch (e: OutOfMemoryError) {
			// Staging buffer / bitmap copy did not fit: drop our buffers and hand the tile back unfiltered.
			Log.w(TAG, "GPU filter out of memory — tile returned unfiltered", e)
			trimLocked(false)
			unfiltered(bitmap, cropRect)
		}
	}

	/** A trim that arrived while a tile was rendering is applied right after it, under the same lock. */
	private fun applyPendingTrim() {
		val level = pendingTrim
		if (level == TRIM_NONE) return
		pendingTrim = TRIM_NONE
		if (!isReleased && ready) trimLocked(level == TRIM_FULL)
	}

	private fun unfiltered(bitmap: Bitmap, cropRect: Rect?): Bitmap =
		if (cropRect == null) bitmap else cropUnfiltered(bitmap, cropRect)

	/**
	 * Tiles the GPU chain cannot or need not touch: hardware bitmaps (not readable), tiny tiles (setup costs more
	 * than it gives), non-sRGB colour spaces (the shaders assume sRGB), and flat pure white/black tiles (manga
	 * margins), which every spatial filter leaves unchanged.
	 */
	private fun shouldSkip(b: Bitmap): Boolean {
		if (b.config == Bitmap.Config.HARDWARE) return true
		if (b.width.toLong() * b.height < MIN_FILTER_PIXELS) return true
		if (Build.VERSION.SDK_INT >= 26) {
			val cs = b.colorSpace
			if (cs != null && !cs.isSrgb) return true
		}
		// Deband adds grain even to flat areas, so it must see every tile to stay seamless.
		return !debandActive() && isFlatExtreme(b)
	}

	private fun isFlatExtreme(b: Bitmap): Boolean {
		val w = b.width
		val h = b.height
		val first = b.getPixel(0, 0)
		if (first != -0x1 && first != -0x1000000) return false // opaque white or opaque black only
		val row = IntArray(w)
		for (y in 0 until h) {
			b.getPixels(row, 0, w, 0, y, w, 1)
			for (x in 0 until w) if (row[x] != first) return false
		}
		return true
	}

	/** No filter is active: still honour [cropRect] with a plain CPU crop (no GL needed). */
	private fun cropUnfiltered(
		bitmap: Bitmap,
		cropRect: Rect,
	): Bitmap = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())

	/**
	 * Never blocks the caller (often the main thread): the volatile flag stops new work at once, and when a tile
	 * is rendering right now the actual teardown (eglTerminate, program deletes: can take long on a bad driver)
	 * runs on the gc thread as soon as that tile releases the lock.
	 */
	fun release() {
		isReleased = true
		if (lock.tryLock()) {
			try {
				releaseResourcesLocked()
			} finally {
				lock.unlock()
			}
		} else {
			gcExecutor.execute { lock.withLock { if (isReleased) releaseResourcesLocked() } }
		}
	}

	private fun releaseResourcesLocked() {
		if (callbacksRegistered) {
			(context.applicationContext ?: context).unregisterComponentCallbacks(trimCallbacks)
			callbacksRegistered = false
		}
		releaseLocked()
	}

	// ── Init ──────────────────────────────────────────────────────────────────

	private fun initLocked(): Boolean {
		if (ready) return true
		try {
			eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
			check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

			val versions = IntArray(2)
			check(EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) { "eglInitialize failed" }
			eglInited = true

			val configs = arrayOfNulls<EGLConfig>(1)
			val numConfigs = IntArray(1)
			check(
				EGL14.eglChooseConfig(eglDisplay, CONFIG_ATTRIBS, 0, configs, 0, 1, numConfigs, 0) &&
					numConfigs[0] > 0,
			) { "eglChooseConfig failed" }
			cachedConfig = configs[0]!!

			val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
			eglContext = EGL14.eglCreateContext(eglDisplay, cachedConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
			check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

			eglSurface = createPbuffer(1, 1)

			makeCurrent()

			floatTargets = detectFloatTargets()

			val maxTex = IntArray(1)
			GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTex, 0)
			maxTextureSize = if (maxTex[0] > 0) maxTex[0] else 2048

			loadPersistedFailures()
			quadVbo = buildQuadVbo()
			quadVao = buildQuadVao(quadVbo)

			releaseCurrent()
			ready = true
			if (!callbacksRegistered) {
				(context.applicationContext ?: context).registerComponentCallbacks(trimCallbacks)
				callbacksRegistered = true
			}
			return true
		} catch (e: Exception) {
			Log.e(TAG, "GpuTileRenderer init failed — falling back to unfiltered tiles", e)
			retryAtMs = SystemClock.uptimeMillis() + RETRY_COOLDOWN_MS
			releaseLocked()
			return false
		}
	}

	/**
	 * Color-renderable RGBA16F is core only in OpenGL ES 3.2; on 3.0/3.1 it needs
	 * `EXT_color_buffer_float` or `EXT_color_buffer_half_float`. Without it the edge buffer's FBO
	 * is incomplete, so Adaptive-Sharpen degrades to RCAS instead of leaving tiles unfiltered.
	 */
	private fun detectFloatTargets(): Boolean {
		val major = IntArray(1)
		val minor = IntArray(1)
		GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, major, 0)
		GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, minor, 0)
		if (major[0] > 3 || (major[0] == 3 && minor[0] >= 2)) return true
		val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: return false
		return ext.contains("GL_EXT_color_buffer_float") || ext.contains("GL_EXT_color_buffer_half_float")
	}

	private fun loadShaderSource(name: String): String =
		context.assets
			.open(name)
			.bufferedReader()
			.use { it.readText() }

	private fun buildProgram(fragSrc: String): Int {
		val vs = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SRC)
		val fs = try {
			compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
		} catch (e: Exception) {
			GLES30.glDeleteShader(vs)
			throw e
		}
		val prog = GLES30.glCreateProgram()
		GLES30.glAttachShader(prog, vs)
		GLES30.glAttachShader(prog, fs)
		GLES30.glBindAttribLocation(prog, 0, "a_position")
		GLES30.glBindAttribLocation(prog, 1, "a_texCoord")
		GLES30.glLinkProgram(prog)
		val status = IntArray(1)
		GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
		GLES30.glDeleteShader(vs)
		GLES30.glDeleteShader(fs)
		if (status[0] != GLES30.GL_TRUE) {
			val log = GLES30.glGetProgramInfoLog(prog)
			GLES30.glDeleteProgram(prog)
			error("Program link failed: $log")
		}
		return prog
	}

	private fun compileShader(
		type: Int,
		src: String,
	): Int {
		val shader = GLES30.glCreateShader(type)
		GLES30.glShaderSource(shader, src)
		GLES30.glCompileShader(shader)
		val status = IntArray(1)
		GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
		if (status[0] != GLES30.GL_TRUE) {
			val log = GLES30.glGetShaderInfoLog(shader)
			GLES30.glDeleteShader(shader)
			error("Shader compile failed: $log")
		}
		return shader
	}

	// ── Lazy per-program build + persisted failures ───────────────────────────

	private fun ensureProgram(id: ShaderId, isBuilt: Boolean, build: () -> Unit): Boolean {
		if (isBuilt) return true
		if (id in failedProgs) return false
		return try {
			build()
			true
		} catch (e: Exception) {
			Log.e(TAG, "${id.file} unavailable — its stage is disabled", e)
			failedProgs.add(id)
			persistFailure(id)
			false
		}
	}

	private fun ensureEnhance() = ensureProgram(ShaderId.ENHANCE, progEnhance != 0) {
		val prog = buildProgram(loadShaderSource(ShaderId.ENHANCE.file))
		progEnhance = prog
		enhanceU = EnhanceUniforms(prog)
	}

	private fun ensureDeband() = ensureProgram(ShaderId.DEBAND, progDeband != 0) {
		val prog = buildProgram(loadShaderSource(ShaderId.DEBAND.file))
		progDeband = prog
		debandU = DebandUniforms(prog)
	}

	private fun ensureRcas() = ensureProgram(ShaderId.RCAS, progRcas != 0) {
		val prog = buildProgram(loadShaderSource(ShaderId.RCAS.file))
		progRcas = prog
		rcasU = RcasUniforms(prog)
	}

	private fun ensureEdge() = ensureProgram(ShaderId.EDGE, progEdge != 0) {
		val prog = buildProgram(loadShaderSource(ShaderId.EDGE.file))
		progEdge = prog
		edgeU = EdgeUniforms(prog)
	}

	private fun ensureSharpen() = ensureProgram(ShaderId.SHARPEN, progSharpen != 0) {
		val prog = buildProgram(loadShaderSource(ShaderId.SHARPEN.file))
		progSharpen = prog
		sharpenU = SharpenUniforms(prog)
	}

	private fun ensureBinarize() = ensureProgram(ShaderId.BINARIZE, progBinarize != 0) {
		val prog = buildProgram(loadShaderSource(ShaderId.BINARIZE.file))
		progBinarize = prog
		binarizeU = BinarizeUniforms(prog)
	}

	private fun prefs() = (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

	/**
	 * A driver that rejects a shader keeps rejecting it, so compile failures are remembered per GPU/driver/app build
	 * for [FAILURE_TTL_MS]; a GPU or app update, or the TTL, gives the shader another chance. Needs the GL context
	 * current (reads GL_RENDERER / GL_VERSION).
	 */
	private fun loadPersistedFailures() {
		failedProgs.clear()
		try {
			val renderer = GLES30.glGetString(GLES30.GL_RENDERER).orEmpty()
			val version = GLES30.glGetString(GLES30.GL_VERSION).orEmpty()
			val stamp = try {
				val ctx = context.applicationContext ?: context
				ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
			} catch (_: Exception) {
				0L
			}
			gpuKey = "$renderer|$version|$stamp"
			val now = System.currentTimeMillis()
			val prefix = "$gpuKey#"
			prefs().getStringSet(PREF_FAILED, null)?.forEach { entry ->
				if (!entry.startsWith(prefix)) return@forEach
				val parts = entry.substring(prefix.length).split('#')
				val id = ShaderId.values().firstOrNull { it.file == parts.getOrNull(0) } ?: return@forEach
				val at = parts.getOrNull(1)?.toLongOrNull() ?: return@forEach
				if (now - at in 0 until FAILURE_TTL_MS) failedProgs.add(id)
			}
		} catch (e: Exception) {
			Log.w(TAG, "could not load persisted shader failures", e)
		}
	}

	private fun persistFailure(id: ShaderId) {
		try {
			val now = System.currentTimeMillis()
			val kept = HashSet<String>()
			prefs().getStringSet(PREF_FAILED, null)?.forEach { entry ->
				val at = entry.substringAfterLast('#').toLongOrNull() ?: return@forEach
				if (now - at in 0 until FAILURE_TTL_MS && !entry.startsWith("$gpuKey#${id.file}#")) kept.add(entry)
			}
			kept.add("$gpuKey#${id.file}#$now")
			prefs().edit().putStringSet(PREF_FAILED, kept).apply()
		} catch (e: Exception) {
			Log.w(TAG, "could not persist shader failure", e)
		}
	}

	// ── Idle cleanup (GC-style) ───────────────────────────────────────────────

	/**
	 * Frees GPU memory that has gone unused, without ever blocking a tile: [IDLE_TRIM_MS] after the last filtered
	 * tile the intermediate buffers and the readback buffer are dropped; after [IDLE_RELEASE_MS] the whole GL
	 * context and programs go too. Both come back lazily on the next tile. Runs on a low-priority daemon thread
	 * that exits when idle and only ever tryLocks, so a decode in progress always wins.
	 */
	private fun scheduleGc() {
		if (isReleased || !gcScheduled.compareAndSet(false, true)) return
		try {
			gcExecutor.schedule(Runnable { gcTick() }, IDLE_TRIM_MS, TimeUnit.MILLISECONDS)
		} catch (_: Exception) {
			gcScheduled.set(false)
		}
	}

	private fun gcTick() {
		val nextDelay: Long
		if (isReleased) {
			gcScheduled.set(false)
			return
		}
		if (!lock.tryLock()) {
			nextDelay = GC_BUSY_RETRY_MS
		} else {
			try {
				if (isReleased || !ready) {
					gcScheduled.set(false)
					return
				}
				val idle = SystemClock.uptimeMillis() - lastUseMs
				if (idle >= IDLE_RELEASE_MS) {
					trimLocked(full = true)
					gcScheduled.set(false)
					return
				}
				if (idle >= IDLE_TRIM_MS) trimLocked(full = false)
				nextDelay = if (idle >= IDLE_TRIM_MS) IDLE_RELEASE_MS - idle else IDLE_TRIM_MS - idle
			} finally {
				lock.unlock()
			}
		}
		try {
			gcExecutor.schedule(Runnable { gcTick() }, nextDelay.coerceAtLeast(GC_BUSY_RETRY_MS), TimeUnit.MILLISECONDS)
		} catch (_: Exception) {
			gcScheduled.set(false)
		}
	}

	private fun trimLocked(full: Boolean) {
		if (full) {
			releaseLocked()
			return
		}
		readBuf = null
		if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
		try {
			makeCurrent()
			pingA.release()
			pingB.release()
			edgeBuf.release()
		} catch (_: Exception) {
		} finally {
			releaseCurrent()
		}
	}

	/**
	 * Memory-pressure hook (e.g. from `onTrimMemory`): drops buffers now, or everything when [full], unless a tile is
	 * being filtered right now (then it does nothing; the idle cleanup will catch up).
	 */
	public fun trimMemory(full: Boolean = false) {
		if (!lock.tryLock()) {
			// A tile is rendering: remember the strongest request, applyPendingTrim() runs it right after the tile.
			pendingTrim = maxOf(pendingTrim, if (full) TRIM_FULL else TRIM_PARTIAL)
			return
		}
		try {
			pendingTrim = TRIM_NONE
			if (!isReleased && ready) trimLocked(full)
		} finally {
			lock.unlock()
		}
	}

	/** Full-screen quad: (position.xy, texCoord.xy) interleaved, TRIANGLE_STRIP winding. */
	private fun buildQuadVbo(): Int {
		val data: FloatBuffer =
			ByteBuffer
				.allocateDirect(4 * 4 * 4)
				.order(ByteOrder.nativeOrder())
				.asFloatBuffer()
				.apply {
					put(
						floatArrayOf(
							-1f,
							-1f,
							0f,
							0f,
							1f,
							-1f,
							1f,
							0f,
							-1f,
							1f,
							0f,
							1f,
							1f,
							1f,
							1f,
							1f,
						),
					)
					position(0)
				}
		val vbo = IntArray(1)
		GLES30.glGenBuffers(1, vbo, 0)
		GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
		GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 4 * 4 * 4, data, GLES30.GL_STATIC_DRAW)
		GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
		return vbo[0]
	}

	private fun buildQuadVao(vbo: Int): Int {
		val vao = IntArray(1)
		val stride = 4 * 4
		GLES30.glGenVertexArrays(1, vao, 0)
		GLES30.glBindVertexArray(vao[0])
		GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
		GLES30.glEnableVertexAttribArray(0)
		GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
		GLES30.glEnableVertexAttribArray(1)
		GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 2 * 4)
		GLES30.glBindVertexArray(0)
		GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
		return vao[0]
	}

	// ── Render ────────────────────────────────────────────────────────────────

	private fun applyFilterLocked(
		src: Bitmap,
		cropRect: Rect?,
		sharpenCap: Float,
		originX: Int,
		originY: Int,
	): Bitmap {
		val w = src.width
		val h = src.height
		// Too large for this GPU: return unfiltered (still honouring the crop) rather than
		// failing the decode.
		if (w > maxTextureSize || h > maxTextureSize) {
			return if (cropRect == null) src else cropUnfiltered(src, cropRect)
		}
		require(
			cropRect == null || (
				cropRect.left >= 0 && cropRect.top >= 0 &&
					cropRect.right <= w && cropRect.bottom <= h &&
					!cropRect.isEmpty
			),
		) { "cropRect $cropRect out of bounds for ${w}x$h" }

		makeCurrent()

		val srcTexIds = IntArray(1)
		try {
			// Upload source bitmap to GL texture
			GLES30.glGenTextures(1, srcTexIds, 0)
			val srcTex = srcTexIds[0]
			GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
			GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
			GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
			GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
			GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

			// GLUtils.texImage2D takes ARGB_8888 and RGB_565 as they are (565 uploads as an opaque RGB texture);
			// anything else (A8, F16, ...) is converted first.
			val upload =
				if (src.config == Bitmap.Config.ARGB_8888 || src.config == Bitmap.Config.RGB_565) {
					src
				} else {
					src.copy(Bitmap.Config.ARGB_8888, false)
				}
			try {
				android.opengl.GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, upload, 0)
			} finally {
				if (upload !== src) upload.recycle()
			}

			GLES30.glViewport(0, 0, w, h)
			GLES30.glBindVertexArray(quadVao)

			var currentTex = srcTex
			var currentFbo = 0
			var pingIsA = true // which of pingA/pingB is "next free" to render into

			fun nextPing(): FboTex {
				val target = if (pingIsA) pingA else pingB
				target.ensureSize(w, h)
				pingIsA = !pingIsA
				return target
			}

			// Which body stage (if any) will run last, so vibrance — being purely pointwise, see
			// the "Vibrance fusion" note above — can be folded into ITS draw call instead of
			// paying for a whole extra pass just to apply a per-pixel-only formula. If no body
			// stage runs (vibrance is the only active filter), it still needs its own standalone
			// pass — that path is unavoidable and unchanged from before this optimisation.
			// No renderable float target -> Adaptive-Sharpen is replaced by RCAS (same category).
			// A stage whose shader cannot be built drops out HERE, before fusion is decided: adaptive-sharpen
			// falls back to RCAS, the rest are simply skipped.
			val doDeband = debandActive() && ensureDeband()
			val wantVibrance = vibranceActive()
			val doPre = (enableDenoise || enableDarken) && ensureEnhance()
			val useAdaptive = enableAdaptiveSharpen && floatTargets && ensureEdge() && ensureSharpen()
			val rcasAmount = if (enableRcas) rcasIntensity else maxOf(rcasIntensity, adaptiveSharpenIntensity)
			val useRcas = (enableRcas || (enableAdaptiveSharpen && !useAdaptive)) && rcasAmount * sharpenCap > 0f && ensureRcas()
			val lastBodyStage =
				when {
					useAdaptive -> Stage.ADAPTIVE_SHARPEN
					useRcas -> Stage.RCAS
					doPre -> Stage.PRE_ENHANCE
					doDeband -> Stage.DEBAND
					else -> Stage.NONE
				}
			val fuseVibrance = wantVibrance && lastBodyStage != Stage.NONE

			// Deband runs FIRST: sharpening a still-visible band edge would only make it more
			// visible, and denoise's luma-weighted blur is a different (noise, not
			// quantization-step) problem so ordering relative to it matters less.
			if (doDeband) {
				val out = nextPing()
				// See deband.frag's u_range doc: capped to the 4px apron GpuFilteringDecoder
				// actually decodes, not f3kdb's own recommended range of 15 — a deliberate
				// trade-off to stay seam-safe (Item 7) without adding decode overhead to every
				// tile just for this filter.
				val range = 1f + debandIntensity.coerceIn(0f, 1f) * 3f
				val threshold = (debandIntensity.coerceIn(0f, 1f) * 64f) / 255f
				val grain = (debandIntensity.coerceIn(0f, 1f) * 32f) / 255f
				val fuseHere = fuseVibrance && lastBodyStage == Stage.DEBAND
				drawDeband(currentTex, out.fbo, w, h, range, threshold, grain, originX, originY, fuseHere)
				currentTex = out.texture
				currentFbo = out.fbo
			}

			// Pre pass: denoise / darken. Skipped entirely (no draw call, no FBO) if neither is on.
			if (doPre) {
				val out = nextPing()
				val fuseHere = fuseVibrance && lastBodyStage == Stage.PRE_ENHANCE
				drawEnhance(currentTex, out.fbo, w, h, denoise = enableDenoise, darken = enableDarken, vibrance = fuseHere)
				currentTex = out.texture
				currentFbo = out.fbo
			}

			// RCAS
			if (useRcas) {
				val out = nextPing()
				val fuseHere = fuseVibrance && lastBodyStage == Stage.RCAS
				drawRcas(currentTex, out.fbo, w, h, rcasAmount * sharpenCap, fuseHere)
				currentTex = out.texture
				currentFbo = out.fbo
			}

			// Adaptive-Sharpen: edge pass (RGBA16F, never the fusion target — it's always an
			// intermediate pass feeding the sharpen pass) then sharpen pass (RGBA8).
			if (useAdaptive) {
				edgeBuf.ensureSize(w, h)
				drawEdge(currentTex, edgeBuf.fbo, w, h)
				val out = nextPing()
				// curve_height in bacondither's own sane range [0.3, 2.0] (ledger #2); intensity
				// 0..1 spans that full range linearly.
				val curveHeight = 0.3f + (adaptiveSharpenIntensity.coerceIn(0f, 1f) * sharpenCap) * 1.7f
				val fuseHere = fuseVibrance && lastBodyStage == Stage.ADAPTIVE_SHARPEN
				drawSharpen(edgeBuf.texture, out.fbo, w, h, curveHeight, fuseHere)
				currentTex = out.texture
				currentFbo = out.fbo
			}

			// Standalone vibrance pass — ONLY when vibrance is the sole active filter (fuseVibrance
			// is false exactly when lastBodyStage is NONE, i.e. no other stage ran to fuse into).
			if (wantVibrance && !fuseVibrance && ensureEnhance()) {
				val out = nextPing()
				drawEnhance(currentTex, out.fbo, w, h, denoise = false, darken = false, vibrance = true)
				currentTex = out.texture
				currentFbo = out.fbo
			}

			// Binarize: last, so it thresholds the finished (sharpened, vibrance-adjusted) pixels.
			if (enableBinarize && ensureBinarize()) {
				val out = nextPing()
				drawBinarize(currentTex, out.fbo, w, h, binarizeStrength.coerceIn(0f, 1f))
				currentTex = out.texture
				currentFbo = out.fbo
			}

			GLES30.glBindVertexArray(0)

			// Only a stage that could not be built was enabled: nothing was drawn, return the tile unfiltered.
			if (currentFbo == 0) return if (cropRect == null) src else cropUnfiltered(src, cropRect)

			// Read back whichever FBO the last stage wrote into. hasActiveFilter() guarantees at
			// least one stage ran, so currentFbo is never 0 here.
			GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, currentFbo)

			// The quad's texcoord mapping (NDC y=-1 -> v=0) combined with GLUtils.texImage2D's
			// top-down bitmap upload renders the tile flipped once into the framebuffer;
			// glReadPixels' bottom-up readback convention exactly cancels that flip — a source
			// row r lands at window y=r, and reading window y=r back out gives buffer row r
			// directly, so no manual flip is needed even for a partial-height read (verified
			// empirically with a row-marker texture; see the project history for the harness
			// output). Every stage samples and writes with the same quad/texcoord convention, so
			// this cancellation holds identically regardless of how many passes ran.
			GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, currentFbo)

			val readLeft: Int
			val readTop: Int
			val readW: Int
			val readH: Int
			if (cropRect != null) {
				readLeft = cropRect.left
				readTop = cropRect.top
				readW = cropRect.width()
				readH = cropRect.height()
			} else {
				readLeft = 0
				readTop = 0
				readW = w
				readH = h
			}
			val totalBytes = readW * readH * 4
			val pixelBuf = acquireReadBuf(totalBytes)
			// Drain stale errors, then fail the tile (=> unfiltered) instead of returning a black one.
			var drain = 0
			while (GLES30.glGetError() != GLES30.GL_NO_ERROR && ++drain < 8) { /* drain */ }
			GLES30.glReadPixels(readLeft, readTop, readW, readH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixelBuf)
			val glErr = GLES30.glGetError()
			check(glErr == GLES30.GL_NO_ERROR) { "glReadPixels failed: 0x${glErr.toString(16)}" }
			pixelBuf.position(0)

			val result = Bitmap.createBitmap(readW, readH, Bitmap.Config.ARGB_8888)
			result.copyPixelsFromBuffer(pixelBuf)
			return result
		} finally {
			// Always free the source texture and detach the context, even on failure —
			// otherwise the context stays current on this thread and every later call from
			// another thread would fail in eglMakeCurrent. Intermediate ping-pong/edge buffers
			// are NOT freed here: they are reused across calls (see FboTex.ensureSize) and are
			// only released in releaseLocked().
			if (srcTexIds[0] != 0) GLES30.glDeleteTextures(1, srcTexIds, 0)
			releaseCurrent()
		}
	}

	private fun bindQuadInput(
		texture: Int,
		texelW: Int,
		texelH: Int,
		uTexture: Int,
		uTexelSize: Int,
	) {
		GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
		GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
		GLES30.glUniform1i(uTexture, 0)
		GLES30.glUniform2f(uTexelSize, 1f / texelW, 1f / texelH)
	}

	private fun drawEnhance(
		inputTex: Int,
		outputFbo: Int,
		w: Int,
		h: Int,
		denoise: Boolean,
		darken: Boolean,
		vibrance: Boolean,
	) {
		val u = enhanceU!!
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
		GLES30.glUseProgram(progEnhance)
		bindQuadInput(inputTex, w, h, u.uTexture, u.uTexelSize)
		GLES30.glUniform1i(u.uDenoise, if (denoise) 1 else 0)
		GLES30.glUniform1i(u.uDarken, if (darken) 1 else 0)
		GLES30.glUniform1i(u.uVibrance, if (vibrance) 1 else 0)
		GLES30.glUniform1f(u.uDenoiseStrength, denoiseStrength)
		GLES30.glUniform1f(u.uVibranceIntensity, vibranceIntensity)
		GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
	}

	private fun drawDeband(
		inputTex: Int,
		outputFbo: Int,
		w: Int,
		h: Int,
		range: Float,
		threshold: Float,
		grain: Float,
		originX: Int,
		originY: Int,
		fuseVibrance: Boolean,
	) {
		val u = debandU!!
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
		GLES30.glUseProgram(progDeband)
		bindQuadInput(inputTex, w, h, u.uTexture, u.uTexelSize)
		GLES30.glUniform1f(u.uRange, range)
		GLES30.glUniform1f(u.uThreshold, threshold)
		GLES30.glUniform1f(u.uGrain, grain)
		GLES30.glUniform2f(u.uOrigin, originX.toFloat(), originY.toFloat())
		GLES30.glUniform1i(u.uEnableVibrance, if (fuseVibrance) 1 else 0)
		GLES30.glUniform1f(u.uVibranceIntensity, vibranceIntensity)
		GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
	}

	private fun drawRcas(
		inputTex: Int,
		outputFbo: Int,
		w: Int,
		h: Int,
		intensity: Float,
		fuseVibrance: Boolean,
	) {
		val u = rcasU!!
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
		GLES30.glUseProgram(progRcas)
		bindQuadInput(inputTex, w, h, u.uTexture, u.uTexelSize)
		// AMD's FsrRcasCon(sharpness) = exp2(-sharpness), where sharpness is in "stops" and
		// con=1.0 is already AMD's own maximum-sharpness point (sharpness=0 stops) — this makes
		// a direct 0..1 linear mapping of our intensity slider onto u_rcasCon both monotonic and
		// bounded to AMD's full documented effect range, with no extra stops-to-fraction
		// conversion needed.
		GLES30.glUniform1f(u.uRcasCon, intensity.coerceIn(0f, 1f))
		GLES30.glUniform1i(u.uEnableVibrance, if (fuseVibrance) 1 else 0)
		GLES30.glUniform1f(u.uVibranceIntensity, vibranceIntensity)
		GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
	}

	private fun drawBinarize(
		inputTex: Int,
		outputFbo: Int,
		w: Int,
		h: Int,
		strength: Float,
	) {
		val u = binarizeU!!
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
		GLES30.glUseProgram(progBinarize)
		bindQuadInput(inputTex, w, h, u.uTexture, u.uTexelSize)
		GLES30.glUniform1f(u.uStrength, strength)
		// Inside the 4 px apron GpuFilteringDecoder decodes around every tile (seam-safe).
		GLES30.glUniform1f(u.uRadius, BINARIZE_RADIUS_PX)
		GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
	}

	private fun drawEdge(
		inputTex: Int,
		outputFbo: Int,
		w: Int,
		h: Int,
	) {
		val u = edgeU!!
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
		GLES30.glUseProgram(progEdge)
		bindQuadInput(inputTex, w, h, u.uTexture, u.uTexelSize)
		GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
	}

	private fun drawSharpen(
		inputTex: Int,
		outputFbo: Int,
		w: Int,
		h: Int,
		curveHeight: Float,
		fuseVibrance: Boolean,
	) {
		val u = sharpenU!!
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
		GLES30.glUseProgram(progSharpen)
		bindQuadInput(inputTex, w, h, u.uTexture, u.uTexelSize)
		GLES30.glUniform1f(u.uCurveHeight, curveHeight)
		GLES30.glUniform1i(u.uEnableVibrance, if (fuseVibrance) 1 else 0)
		GLES30.glUniform1f(u.uVibranceIntensity, vibranceIntensity)
		GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
	}

	// ── EGL helpers ───────────────────────────────────────────────────────────

	private fun createPbuffer(
		w: Int,
		h: Int,
	): EGLSurface {
		val attrs = intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE)
		val s = EGL14.eglCreatePbufferSurface(eglDisplay, cachedConfig, attrs, 0)
		check(s != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface($w,$h) failed" }
		return s
	}

	private fun makeCurrent() {
		check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
			"eglMakeCurrent failed: 0x${EGL14.eglGetError().toString(16)}"
		}
	}

	private fun releaseCurrent() {
		EGL14.eglMakeCurrent(
			eglDisplay,
			EGL14.EGL_NO_SURFACE,
			EGL14.EGL_NO_SURFACE,
			EGL14.EGL_NO_CONTEXT,
		)
	}

	/** A direct buffer of exactly [size] bytes (limit), reusing the retained one when big enough. */
	private fun acquireReadBuf(size: Int): ByteBuffer {
		val cur = readBuf
		if (cur != null && cur.capacity() >= size) {
			cur.clear()
			cur.limit(size)
			return cur
		}
		val fresh = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
		// Very large reads (whole-page decodes) are not kept alive between calls.
		readBuf = if (size <= MAX_RETAINED_READ_BYTES) fresh else null
		return fresh
	}

	private fun releaseLocked() {
		ready = false
		readBuf = null
		if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
		try {
			makeCurrent()
		} catch (_: Exception) {
		}
		if (progEnhance != 0) {
			GLES30.glDeleteProgram(progEnhance)
			progEnhance = 0
		}
		if (progDeband != 0) {
			GLES30.glDeleteProgram(progDeband)
			progDeband = 0
		}
		if (progBinarize != 0) {
			GLES30.glDeleteProgram(progBinarize)
			progBinarize = 0
		}
		if (progRcas != 0) {
			GLES30.glDeleteProgram(progRcas)
			progRcas = 0
		}
		if (progEdge != 0) {
			GLES30.glDeleteProgram(progEdge)
			progEdge = 0
		}
		if (progSharpen != 0) {
			GLES30.glDeleteProgram(progSharpen)
			progSharpen = 0
		}
		enhanceU = null
		debandU = null
		binarizeU = null
		rcasU = null
		edgeU = null
		sharpenU = null
		pingA.release()
		pingB.release()
		edgeBuf.release()
		if (quadVao != 0) {
			GLES30.glDeleteVertexArrays(1, intArrayOf(quadVao), 0)
			quadVao = 0
		}
		if (quadVbo != 0) {
			GLES30.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
			quadVbo = 0
		}
		releaseCurrent()
		if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
		if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
		if (eglInited) EGL14.eglTerminate(eglDisplay)
		eglInited = false
		eglSurface = EGL14.EGL_NO_SURFACE
		eglContext = EGL14.EGL_NO_CONTEXT
		eglDisplay = EGL14.EGL_NO_DISPLAY
		cachedConfig = null
		maxTextureSize = Int.MAX_VALUE
	}

	companion object {
		private const val TAG = "GpuTileRenderer"
		private const val MAX_RETAINED_READ_BYTES = 16 * 1024 * 1024
		private const val TRIM_NONE = 0
		private const val TRIM_PARTIAL = 1
		private const val TRIM_FULL = 2
		private const val MIN_FILTER_PIXELS = 64L * 64
		private const val MAX_CONSECUTIVE_ERRORS = 3
		private const val RETRY_COOLDOWN_MS = 5L * 60 * 1000
		private const val FAILURE_TTL_MS = 7L * 24 * 60 * 60 * 1000
		private const val IDLE_TRIM_MS = 30_000L
		private const val IDLE_RELEASE_MS = 5L * 60 * 1000
		private const val GC_BUSY_RETRY_MS = 5_000L
		private const val PREFS = "ssiv_gpu_shaders"
		private const val PREF_FAILED = "failed"

		/** Neighbour distance of binarize.frag; must stay below the 4 px apron of [GpuFilteringDecoder]. */
		private const val BINARIZE_RADIUS_PX = 3f

		// Shared across all instances — allocated once at class load time.
		private val CONFIG_ATTRIBS =
			intArrayOf(
				EGL14.EGL_RENDERABLE_TYPE,
				EGL14.EGL_OPENGL_ES2_BIT,
				EGL14.EGL_SURFACE_TYPE,
				EGL14.EGL_PBUFFER_BIT,
				EGL14.EGL_RED_SIZE,
				8,
				EGL14.EGL_GREEN_SIZE,
				8,
				EGL14.EGL_BLUE_SIZE,
				8,
				EGL14.EGL_ALPHA_SIZE,
				8,
				EGL14.EGL_DEPTH_SIZE,
				0,
				EGL14.EGL_NONE,
			)

		private val VERTEX_SRC =
			"""
			#version 300 es
			in vec2 a_position;
			in vec2 a_texCoord;
			out vec2 v_texCoord;
			void main() {
				v_texCoord = a_texCoord;
				gl_Position = vec4(a_position, 0.0, 1.0);
			}
			""".trimIndent()
	}
}
