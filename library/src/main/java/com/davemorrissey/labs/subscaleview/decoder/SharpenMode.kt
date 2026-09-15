package com.davemorrissey.labs.subscaleview.decoder

/**
 * GPU sharpen mode, matching `manga_enhance.frag`'s `u_sharpenMode` uniform.
 *
 * Public (not `internal`): the app module stores this directly on
 * `ReaderColorFilter` and passes [glslId] through to [GpuTileRenderer.sharpenMode] /
 * [GpuFilteringDecoder.Factory], so this is the single source of truth for the
 * mode <-> shader-uniform mapping.
 */
public enum class SharpenMode(public val glslId: Int) {
    /** No sharpening. */
    OFF(0),
    /** Hybrid RCAS + Unsharp-Mask — fast, good ringing prevention. Recommended default. */
    RCAS_USM(1),
    /** Adaptive edge-aware sharpening — slightly slower but follows local contrast. */
    ADAPTIVE_SHARPEN(2);

    public companion object {
        public fun fromGlslId(id: Int): SharpenMode = entries.firstOrNull { it.glslId == id } ?: OFF
    }
}
