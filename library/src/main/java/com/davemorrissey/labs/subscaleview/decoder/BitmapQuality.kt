package com.davemorrissey.labs.subscaleview.decoder

import android.graphics.Bitmap

/**
 * Controls the color depth used when decoding bitmap tiles and full images.
 *
 * | Level          | Config      | Bits/px | Notes                                          |
 * |----------------|-------------|---------|------------------------------------------------|
 * | [STANDARD]     | ARGB_8888   | 32      | **Default.** Full alpha, correct manga colors. |
 * | [MEMORY_SAVING]| RGB_565     | 16      | No alpha, visible color banding. Avoid unless  |
 * |                |             |         | device RAM is critically low.                  |
 *
 * **[STANDARD]** (ARGB_8888) is the correct choice for all manga and webtoon content.
 * It provides full 8-bit precision per channel, correct alpha, and no visible banding.
 * This is used by default on all normal devices.
 *
 * **[MEMORY_SAVING]** (RGB_565) strips the alpha channel and produces visible color
 * banding in gradients and skin tones. Only use this on severely RAM-constrained devices
 * (typically detected automatically via [android.app.ActivityManager.isLowRamDevice]).
 *
 * Note: RGBA_F16 (previously "HIGH") has been removed. It doubles memory consumption
 * per tile and provides no visible benefit on standard sRGB manga/webtoon content.
 * ARGB_8888 is the correct and sufficient format for all supported content.
 */
public enum class BitmapQuality {

    /** ARGB_8888 — 32 bpp, full alpha, correct colors. The recommended default. */
    STANDARD,

    /** RGB_565 — 16 bpp, no alpha, visible banding. Use only on low-RAM devices. */
    MEMORY_SAVING;

    /**
     * Resolves this quality level to a concrete [Bitmap.Config] for the current device.
     */
    internal fun toBitmapConfig(): Bitmap.Config = when (this) {
        STANDARD -> Bitmap.Config.ARGB_8888
        MEMORY_SAVING -> Bitmap.Config.RGB_565
    }
}
