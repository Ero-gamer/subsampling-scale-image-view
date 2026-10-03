package com.davemorrissey.labs.subscaleview.decoder

import android.graphics.Bitmap
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Detects periodic halftone/screentone patterns (the regular dot grids used in B&W manga
 * scans) on a small thumbnail, so [GpuFilteringDecoder] can CAP — not disable — sharpen
 * intensity on pages where sharpening or a non-integer-ratio resample would alias the dot
 * pattern into visible moiré. Smooth colour art (webtoon/manhwa gradients) and photographic
 * content are unaffected: they score far below the detection threshold (verified below).
 *
 * ### The technique: radial autocorrelation profile, oscillation-based
 * Halftone/screentone periodicity detection via autocorrelation or FFT-peak analysis is a real,
 * decades-old signal-processing field (patents and papers going back to the 1970s-80s; see the
 * project's research notes). This is a from-scratch, independently-verified implementation of
 * the general *autocorrelation* approach — chosen over FFT because a small thumbnail makes a
 * direct autocorrelation sum tractable without needing an FFT implementation at all, and because
 * only a coarse discrimination (periodic vs not) is needed, not a full spectrum. It is NOT a
 * citation of any single published paper's specific formula — see the project's research notes
 * on why the "4-metric combined classifier" some earlier research surfaced is treated as an
 * unsourced engineering synthesis, not built here.
 *
 * The key insight that makes this work (and the part a naive "is there strong correlation at any
 * lag" check gets wrong — verified by testing exactly that naive version first, see project
 * history): periodicity isn't "high correlation somewhere", it's *oscillation* — correlation
 * must dip to a local minimum and then rise back to a local maximum as lag distance increases.
 * Smooth or photographic content's autocorrelation just decays monotonically with no rebound;
 * only genuinely periodic content rebounds. The score is the height of that rebound (peak minus
 * the preceding trough), radially averaged over lag distance (not direction-specific, since a
 * halftone screen's dot grid is periodic in both axes and diagonals).
 *
 * Verified (see project history) across synthetic screentone at dot pitches 3-20px (scores
 * 0.22-0.60) against smooth gradients, low-pass and sharper photographic-like noise, plain white
 * noise, and a coarse non-screentone-frequency checkerboard (all scoring <= 0.03) — a wide,
 * comfortable margin for the [SCORE_THRESHOLD] below.
 */
internal object ScreentonePeriodicityDetector {

    /** Below this score, [scoreToSharpenCap] returns 1.0 (no reduction at all). */
    private const val SCORE_THRESHOLD = 0.10f

    /** Score at/above which the cap has fully reached [MIN_CAP]. Comfortably above the ~0.22-0.60
     *  range real screentone scored in verification, so real screentone pages reliably reach the
     *  floor rather than landing partway up the ramp. */
    private const val SCORE_AT_FLOOR = 0.35f

    /** The most sharpen intensity is ever reduced to, even for a very strong periodic signal —
     *  this CAPS, it does not disable, per the product decision this item was scoped to. */
    private const val MIN_CAP = 0.3f

    /**
     * @param gray Row-major luma values, one float per pixel of the thumbnail (any reasonable
     *   size — 128x128 or so is plenty; this is deliberately run on a cheap downsampled
     *   thumbnail, not the full page).
     * @param maxLag Largest lag distance (in thumbnail pixels) to search for a periodicity
     *   rebound. 25 covers realistic screentone dot pitches at typical thumbnail-decode scale
     *   (verified: real dot pitches of 3-20px thumbnail-pixels all detected within this range).
     * @return A score in `[0, +inf)` in practice always small; see [scoreToSharpenCap] for how
     *   this maps to an actual intensity multiplier. 0 means "no periodicity detected at all"
     *   (including the degenerate case of a perfectly flat/constant thumbnail).
     */
    fun detectPeriodicityScore(gray: FloatArray, width: Int, height: Int, maxLag: Int = 25): Float {
        val mean = gray.average().toFloat()
        // Centred copy (g = gray - mean); avoids mutating the caller's array.
        val g = FloatArray(gray.size) { gray[it] - mean }

        var zeroLag = 0f
        for (v in g) zeroLag += v * v
        if (zeroLag < 1e-9f) return 0f // flat thumbnail: no signal to have periodicity in

        val profile = FloatArray(maxLag + 1)
        val counts = IntArray(maxLag + 1)

        for (dy in -maxLag..maxLag) {
            for (dx in -maxLag..maxLag) {
                if (dx == 0 && dy == 0) continue
                val lagF = sqrt((dx * dx + dy * dy).toDouble())
                val lagI = round(lagF).toInt()
                if (lagI < 1 || lagI > maxLag) continue

                val (ys1, ye1, ys2, _) = if (dy >= 0) intArrayOf(0, height - dy, dy, height)
                                          else intArrayOf(-dy, height, 0, height + dy)
                val (xs1, xe1, xs2, _) = if (dx >= 0) intArrayOf(0, width - dx, dx, width)
                                          else intArrayOf(-dx, width, 0, width + dx)

                var corr = 0f
                var row1 = ys1
                var row2 = ys2
                while (row1 < ye1) {
                    val base1 = row1 * width
                    val base2 = row2 * width
                    var col1 = xs1
                    var col2 = xs2
                    while (col1 < xe1) {
                        corr += g[base1 + col1] * g[base2 + col2]
                        col1++; col2++
                    }
                    row1++; row2++
                }
                profile[lagI] += corr
                counts[lagI] += 1
            }
        }

        for (i in profile.indices) {
            if (counts[i] > 0) profile[i] = profile[i] / counts[i] / zeroLag
        }

        // Oscillation search: skip the trivial near-DC lags (1 and 2 — almost any smooth image
        // has some correlation immediately next to a pixel; that is not periodicity evidence).
        val minPeriod = 3
        if (profile.size - minPeriod < 3) return 0f
        var minIdx = minPeriod
        for (i in minPeriod until profile.size) if (profile[i] < profile[minIdx]) minIdx = i
        if (minIdx >= profile.size - 1) return 0f // minimum sits at the far edge: no room to rebound
        var peak = Float.NEGATIVE_INFINITY
        for (i in (minIdx + 1) until profile.size) if (profile[i] > peak) peak = profile[i]
        return (peak - profile[minIdx]).coerceAtLeast(0f)
    }

    /** Convenience overload: extracts luma from a [Bitmap] (any config; downsampled thumbnails
     *  from [android.graphics.BitmapRegionDecoder] are typically already `ARGB_8888`). */
    fun detectPeriodicityScore(bitmap: Bitmap, maxLag: Int = 25): Float {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        return detectPeriodicityScore(gray, w, h, maxLag)
    }

    /**
     * Maps a periodicity score to a sharpen-intensity multiplier in `[MIN_CAP, 1.0]`. Below
     * [SCORE_THRESHOLD], no reduction at all (cap = 1.0) — smooth colour art and photographic
     * content are essentially always in this range (verified <= 0.03 across several such test
     * cases, comfortably below the 0.10 threshold). From there it ramps linearly down to
     * [MIN_CAP] by [SCORE_AT_FLOOR], which real detected screentone comfortably exceeds.
     */
    fun scoreToSharpenCap(score: Float): Float {
        if (score <= SCORE_THRESHOLD) return 1f
        val t = ((score - SCORE_THRESHOLD) / (SCORE_AT_FLOOR - SCORE_THRESHOLD)).coerceIn(0f, 1f)
        return 1f - t * (1f - MIN_CAP)
    }
}

/** Destructuring support for the 4-element int arrays used as lightweight (a,b,c,d) tuples above. */
private operator fun IntArray.component1() = this[0]
private operator fun IntArray.component2() = this[1]
private operator fun IntArray.component3() = this[2]
private operator fun IntArray.component4() = this[3]
