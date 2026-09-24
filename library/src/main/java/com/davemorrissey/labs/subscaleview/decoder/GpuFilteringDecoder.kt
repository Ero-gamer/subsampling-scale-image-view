package com.davemorrissey.labs.subscaleview.decoder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import androidx.annotation.WorkerThread

/**
 * [ImageRegionDecoder] that wraps a base decoder (libjpeg-turbo or Skia) and applies the
 * [GpuTileRenderer] GPU shader pass to each decoded tile. This replaces [FilteringRegionDecoder]
 * (which did denoise/sharpen/vibrance/dither/grain in CPU pixel loops) with a single-pass
 * OpenGL ES 3.0 fragment shader execution.
 *
 * **What this does NOT replace:** Brightness, Contrast, and Saturation remain as CPU/Canvas
 * `ColorMatrix` paint filters on SSIV — they are cheap, allocation-free, and zero latency.
 *
 * **Shader controls** (set via [Factory]'s constructor — the app's `ReaderSettings.applyBitmapConfig()`
 * is the single call-site that constructs this). Every filter is an independent
 * (enable, intensity) pair; they stack and none excludes another. (Bicubic *scalers* are not
 * decode-time filters — see [com.davemorrissey.labs.subscaleview.ImageScaler].)
 * - Denoise             → 3x3 luma-weighted denoise (range-only, not a true bilateral filter)
 * - Darken              → line darkening (Anime4K-inspired heuristic, not the Anime4K algorithm)
 * - Vibrance            → S-curve + selective chroma boost (RGB-space approximation)
 * - RCAS + USM          → RCAS-style clamped unsharp mask
 * - Adaptive (smoothstep) → adaptive sharpen, smoothstep edge weight
 * - Adaptive (sigmoid)  → adaptive sharpen, true logistic-sigmoid edge weight
 *
 * If the GPU renderer fails to initialise (unsupported driver, OOM), tile decoding falls back
 * to returning the unfiltered bitmap — the image remains fully readable.
 */
public class GpuFilteringDecoder(
    private val inner: ImageRegionDecoder,
    public val renderer: GpuTileRenderer,
) : ImageRegionDecoder {

    override fun init(context: Context, uri: Uri): Point = inner.init(context, uri)

    // The interface provides a default impl for init(context, ImageSource) that delegates to
    // init(context, uri) via source.toUri(context). No override needed here — the default
    // impl will call our init(context, uri) above correctly.

    @WorkerThread
    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        val tile = inner.decodeRegion(sRect, sampleSize)
        return try {
            val filtered = renderer.applyFilter(tile)
            // Renderer returned a new bitmap — recycle the intermediate tile allocation.
            if (filtered !== tile) tile.recycle()
            filtered
        } catch (e: Throwable) {
            // Never let a GPU error strand a tile. Return unfiltered so the image stays readable.
            tile
        }
    }

    override val isReady: Boolean get() = inner.isReady

    override fun recycle() {
        inner.recycle()
        // Do NOT call renderer.release() here — the renderer is shared across multiple
        // decoder instances (one per tile worker thread) for the same SSIV image load.
        // It is released by the Factory when the factory itself is replaced (in applyBitmapConfig).
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    public class Factory(
        private val innerFactory: DecoderFactory<out ImageRegionDecoder>,
        enableDenoise: Boolean  = false,
        enableDarken: Boolean   = false,
        enableVibrance: Boolean = false,
        denoiseStrength: Float   = 0.5f,
        vibranceIntensity: Float = 1f,
        enableRcasUsm: Boolean = false,
        rcasUsmIntensity: Float = 0f,
        enableAdaptiveSmoothstep: Boolean = false,
        adaptiveSmoothstepIntensity: Float = 0f,
        enableAdaptiveSigmoid: Boolean = false,
        adaptiveSigmoidIntensity: Float = 0f,
        // The renderer is shared across all decoder instances produced by this factory so the
        // EGL context is created once per SSIV image load, not once per tile decode worker.
        // Public (not internal): app code in a separate module reads/reuses this renderer
        // (see ReaderSettings.applyBitmapConfig).
        public val renderer: GpuTileRenderer,
    ) : DecoderFactory<GpuFilteringDecoder> {

        init {
            renderer.enableDenoise  = enableDenoise
            renderer.enableDarken   = enableDarken
            renderer.enableVibrance = enableVibrance
            renderer.denoiseStrength   = denoiseStrength
            renderer.vibranceIntensity = vibranceIntensity
            renderer.enableRcasUsm = enableRcasUsm
            renderer.rcasUsmIntensity = rcasUsmIntensity
            renderer.enableAdaptiveSmoothstep = enableAdaptiveSmoothstep
            renderer.adaptiveSmoothstepIntensity = adaptiveSmoothstepIntensity
            renderer.enableAdaptiveSigmoid = enableAdaptiveSigmoid
            renderer.adaptiveSigmoidIntensity = adaptiveSigmoidIntensity
        }

        override val bitmapConfig: Bitmap.Config? get() = innerFactory.bitmapConfig

        override fun make(): GpuFilteringDecoder =
            GpuFilteringDecoder(innerFactory.make(), renderer)
    }
}
