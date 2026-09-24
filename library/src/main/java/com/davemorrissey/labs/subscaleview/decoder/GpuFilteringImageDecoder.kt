package com.davemorrissey.labs.subscaleview.decoder

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.WorkerThread

/**
 * [ImageDecoder] wrapper that applies the [GpuTileRenderer] shader pass to a whole decoded image.
 *
 * SSIV decodes an image through one of two mutually exclusive paths:
 * - **Tiled** ([ImageRegionDecoder], see [GpuFilteringDecoder]) — used when the image is larger than a
 *   single tile or is subsampled.
 * - **Whole-bitmap** ([ImageDecoder]) — used when the image fits in a single tile at full resolution.
 *   This is the path taken by ordinary single pages (including landscape/wide pages), so without
 *   this wrapper those pages were displayed completely unfiltered.
 *
 * If the GPU pass fails for any reason (unsupported driver, texture too large, OOM) the unfiltered
 * bitmap is returned so the image stays readable.
 */
public class GpuFilteringImageDecoder(
    private val inner: ImageDecoder,
    public val renderer: GpuTileRenderer,
) : ImageDecoder {

    @WorkerThread
    override fun decode(context: Context, uri: Uri, sampleSize: Int): Bitmap {
        val bitmap = inner.decode(context, uri, sampleSize)
        return try {
            val filtered = renderer.applyFilter(bitmap)
            if (filtered !== bitmap) bitmap.recycle()
            filtered
        } catch (e: Throwable) {
            bitmap
        }
    }

    public class Factory(
        private val innerFactory: DecoderFactory<out ImageDecoder>,
        // Shared with the region-decoder factory so both paths reuse one EGL context.
        public val renderer: GpuTileRenderer,
    ) : DecoderFactory<GpuFilteringImageDecoder> {

        override val bitmapConfig: Bitmap.Config? get() = innerFactory.bitmapConfig

        override fun make(): GpuFilteringImageDecoder =
            GpuFilteringImageDecoder(innerFactory.make(), renderer)
    }
}
