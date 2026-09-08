package com.davemorrissey.labs.subscaleview.decoder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import com.davemorrissey.labs.subscaleview.ImageSource

/**
 * Legacy CPU filter decoder — **all heavy CPU pixel loops have been removed**.
 *
 * Denoise, Dither, Grain, CPU Sharpening kernels, and Vibrance per-pixel loops previously
 * implemented here caused OOM crashes and UI-thread freezes on large webtoon/manga strips
 * on entry-level hardware (ARM Cortex-A53, 2 GB RAM).
 *
 * These filters are now handled by [GpuFilteringDecoder] + [GpuTileRenderer] (single-pass
 * GLES 3.0 fragment shader `manga_enhance.frag`).
 *
 * This class is retained as a **transparent passthrough** to avoid breaking any caller that
 * still references it. New code should use [GpuFilteringDecoder.Factory] or the GPU API
 * extension functions on [com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView].
 *
 * **Retained (CPU / Canvas ColorMatrix) — not touched:**
 * Brightness, Contrast, Saturation — applied as zero-cost `ColorMatrix` paint filters
 * on the SSIV `Canvas` draw path, with no per-pixel bitmap processing.
 */
@Deprecated(
    message = "Use GpuFilteringDecoder for GPU-accelerated tile filtering. " +
              "FilteringRegionDecoder is now a no-op passthrough.",
    replaceWith = ReplaceWith(
        "GpuFilteringDecoder",
        "com.davemorrissey.labs.subscaleview.decoder.GpuFilteringDecoder",
    ),
)
public class FilteringRegionDecoder(
    private val inner: ImageRegionDecoder,
    // Parameters kept for binary API compatibility — all ignored.
    @Suppress("UNUSED_PARAMETER") sharpening: Float = 0f,
    @Suppress("UNUSED_PARAMETER") vibrance: Float   = 0f,
    @Suppress("UNUSED_PARAMETER") denoise: Float    = 0f,
    @Suppress("UNUSED_PARAMETER") dither: Float     = 0f,
    @Suppress("UNUSED_PARAMETER") grain: Float      = 0f,
) : ImageRegionDecoder {

    override fun init(context: Context, uri: Uri): Point       = inner.init(context, uri)
    override fun init(context: Context, source: ImageSource): Point = inner.init(context, source)
    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap = inner.decodeRegion(sRect, sampleSize)
    override val isReady: Boolean get() = inner.isReady
    override fun recycle() = inner.recycle()

    public class Factory(
        private val innerFactory: DecoderFactory<out ImageRegionDecoder>,
        val sharpening: Float = 0f,
        val vibrance: Float   = 0f,
        val denoise: Float    = 0f,
        val dither: Float     = 0f,
        val grain: Float      = 0f,
    ) : DecoderFactory<FilteringRegionDecoder> {
        override val bitmapConfig: Bitmap.Config? get() = innerFactory.bitmapConfig

        @Suppress("DEPRECATION")
        override fun make() = FilteringRegionDecoder(innerFactory.make(), sharpening, vibrance, denoise, dither, grain)
    }
}
