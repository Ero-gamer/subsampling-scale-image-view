package com.davemorrissey.labs.subscaleview

import com.davemorrissey.labs.subscaleview.decoder.BitmapQuality
import com.davemorrissey.labs.subscaleview.decoder.GpuFilteringDecoder
import com.davemorrissey.labs.subscaleview.decoder.GpuTileRenderer
import com.davemorrissey.labs.subscaleview.decoder.LiJpegTurboRegionDecoder

/**
 * GPU sharpening mode for [SubsamplingScaleImageView.setGpuSharpenMode].
 */
public enum class SharpenMode(internal val glslId: Int) {
    /** No sharpening. */
    OFF(0),
    /** Hybrid RCAS + Unsharp-Mask — fast, good ringing prevention. Recommended default. */
    RCAS_USM(1),
    /** Adaptive edge-aware sharpening — slightly slower but follows local contrast. */
    ADAPTIVE_SHARPEN(2),
}

// ── Per-instance GPU renderer registry ───────────────────────────────────────
//
// WeakHashMap keyed on the SSIV instance: when SSIV is GC'd, the entry is removed.
// Synchronised on the map itself — insertions are infrequent (one per SSIV instance).
private val rendererMap = java.util.WeakHashMap<SubsamplingScaleImageView, GpuTileRenderer>()

private fun SubsamplingScaleImageView.getOrCreateRenderer(): GpuTileRenderer =
    synchronized(rendererMap) {
        // If a GpuFilteringDecoder.Factory is already installed, reuse its renderer —
        // it was created by ReaderSettings.applyBitmapConfig and must not be duplicated.
        val existingFactory = regionDecoderFactory as? GpuFilteringDecoder.Factory
        if (existingFactory != null) return@synchronized existingFactory.renderer
        rendererMap.getOrPut(this) { GpuTileRenderer(context) }
    }

// ── Public GPU control API ────────────────────────────────────────────────────

/**
 * Enable or disable the GPU bilateral denoise pass.
 * Takes effect on the next tile load (or call [SubsamplingScaleImageView.setImage] to reload).
 */
public fun SubsamplingScaleImageView.setGpuDenoiseEnabled(enabled: Boolean) {
    getOrCreateRenderer().enableDenoise = enabled
    applyGpuFactory()
}

/**
 * Enable or disable the Anime4K-style line-darkening pass.
 * Useful for manga/manhwa where ink lines should appear crisper.
 */
public fun SubsamplingScaleImageView.setGpuLineDarkenEnabled(enabled: Boolean) {
    getOrCreateRenderer().enableDarken = enabled
    applyGpuFactory()
}

/**
 * Enable or disable the vibrance / S-curve colour-boost pass.
 */
public fun SubsamplingScaleImageView.setGpuVibranceEnabled(enabled: Boolean) {
    getOrCreateRenderer().enableVibrance = enabled
    applyGpuFactory()
}

/**
 * Set the GPU sharpen mode and intensity (0.0–1.0).
 * [SharpenMode.OFF] disables sharpening regardless of [intensity].
 */
public fun SubsamplingScaleImageView.setGpuSharpenMode(mode: SharpenMode, intensity: Float) {
    val r = getOrCreateRenderer()
    r.sharpenMode = mode.glslId
    r.sharpness   = intensity.coerceIn(0f, 1f)
    applyGpuFactory()
}

// ── Internal helper ───────────────────────────────────────────────────────────

/**
 * Installs (or updates) a [GpuFilteringDecoder.Factory] on this SSIV instance.
 * If no GPU filter is active, reverts to the base [LiJpegTurboRegionDecoder.Factory].
 *
 * Call-sites: only the four public GPU API extension functions above.
 * [ReaderSettings.applyBitmapConfig] manages the factory directly and does not call this.
 */
private fun SubsamplingScaleImageView.applyGpuFactory() {
    val r = getOrCreateRenderer()
    val anyActive = r.enableDenoise || r.enableDarken || r.enableVibrance || r.sharpenMode != 0
    val current   = regionDecoderFactory

    // Derive quality from the currently installed factory so we don't downgrade by accident.
    val quality = when (current.bitmapConfig) {
        android.graphics.Bitmap.Config.RGB_565 -> BitmapQuality.MEMORY_SAVING
        else                                   -> BitmapQuality.STANDARD
    }

    if (anyActive) {
        val newFactory = GpuFilteringDecoder.Factory(
            innerFactory   = LiJpegTurboRegionDecoder.Factory(quality),
            enableDenoise  = r.enableDenoise,
            enableDarken   = r.enableDarken,
            enableVibrance = r.enableVibrance,
            sharpenMode    = r.sharpenMode,
            sharpness      = r.sharpness,
            renderer       = r,
        )
        // Avoid reinstalling an identical factory — prevents spurious tile reloads.
        if (current is GpuFilteringDecoder.Factory &&
            current.enableDenoise  == r.enableDenoise  &&
            current.enableDarken   == r.enableDarken   &&
            current.enableVibrance == r.enableVibrance &&
            current.sharpenMode    == r.sharpenMode    &&
            current.sharpness      == r.sharpness) return
        regionDecoderFactory = newFactory
    } else {
        if (current !is GpuFilteringDecoder.Factory) return  // already base, nothing to do
        r.release()
        synchronized(rendererMap) { rendererMap.remove(this) }
        regionDecoderFactory = LiJpegTurboRegionDecoder.Factory(quality)
    }
}
