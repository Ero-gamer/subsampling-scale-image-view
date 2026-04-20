package com.davemorrissey.labs.subscaleview.decoder

import android.graphics.Bitmap
import android.os.Build

/**
 * Controls the color depth used when decoding bitmap tiles and full images.
 *
 * | Level          | Config      | Bits/px | Notes                                          |
 * |----------------|-------------|---------|------------------------------------------------|
 * | [STANDARD]     | ARGB_8888   | 32      | **Default.** Full alpha, correct manga colors. |
 * | [HIGH]         | RGBA_F16    | 64      | Wide-gamut HDR. API 26+ only. High memory.     |
 * | [MEMORY_SAVING]| RGB_565     | 16      | No alpha, visible color banding. Avoid.        |
 *
 * **[STANDARD]** (ARGB_8888) is the correct choice for all manga and webtoon content.
 *
 * **[HIGH]** (RGBA_F16) doubles memory consumption per tile and is only visually
 * distinguishable on displays with P3 or Rec.2020 color gamut. Standard sRGB manga
 * shows no benefit. Falls back to ARGB_8888 below API 26.
 *
 * **[MEMORY_SAVING]** (RGB_565) strips the alpha channel and produces visible color
 * banding in gradients and skin tones. Only use this on severely RAM-constrained devices
 * (< 512 MB) where OOM is a real risk.
 */
 
public enum class BitmapQuality {

    STANDARD,

    MEMORY_SAVING;

    internal fun toBitmapConfig(): Bitmap.Config = when (this) {
        STANDARD -> Bitmap.Config.ARGB_8888
        MEMORY_SAVING -> Bitmap.Config.RGB_565
    }
}
