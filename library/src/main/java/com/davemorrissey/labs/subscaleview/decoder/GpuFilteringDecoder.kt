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
 * **Shader controls** (set via the GPU API extension functions or [ReaderSettings]):
 * - Denoise  → bilateral spatial filter
 * - Darken   → Anime4K-style line darkening
 * - Vibrance → S-curve + selective chroma boost
 * - Sharpen  → RCAS+USM (mode 1) or Adaptive (mode 2) at given intensity
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
        sharpenMode: Int        = 0,
        sharpness: Float        = 0f,
        // The renderer is shared across all decoder instances produced by this factory so the
        // EGL context is created once per SSIV image load, not once per tile decode worker.
        // Public (not internal): app code in a separate module reads/reuses this renderer
        // (see ReaderSettings.applyBitmapConfig and GpuFilterExt.applyGpuFactory).
        public val renderer: GpuTileRenderer,
    ) : DecoderFactory<GpuFilteringDecoder> {

        init {
            renderer.enableDenoise  = enableDenoise
            renderer.enableDarken   = enableDarken
            renderer.enableVibrance = enableVibrance
            renderer.sharpenMode    = sharpenMode
            renderer.sharpness      = sharpness
        }

        override val bitmapConfig: Bitmap.Config? get() = innerFactory.bitmapConfig

        override fun make(): GpuFilteringDecoder =
            GpuFilteringDecoder(innerFactory.make(), renderer)

        /** Expose renderer state for equality checks in applyBitmapConfig / applyGpuFactory. */
        val enableDenoise:  Boolean get() = renderer.enableDenoise
        val enableDarken:   Boolean get() = renderer.enableDarken
        val enableVibrance: Boolean get() = renderer.enableVibrance
        val sharpenMode:    Int     get() = renderer.sharpenMode
        val sharpness:      Float   get() = renderer.sharpness
    }
}
