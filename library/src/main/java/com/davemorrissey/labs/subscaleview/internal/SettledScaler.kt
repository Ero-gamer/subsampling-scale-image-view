package com.davemorrissey.labs.subscaleview.internal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import com.davemorrissey.labs.subscaleview.ImageScaler

/**
 * Scaler for Android < 13 (no AGSL): once the view has been still for [SETTLE_DELAY_MS], the
 * visible part of the image is resampled with the bicubic kernel on the GPU ([BicubicRenderer])
 * into one overlay bitmap that is then drawn over the normal (bilinear) content.
 *
 * While the user pinches, pans or flings, the normal bilinear path is used untouched; the overlay
 * is only drawn while the view state is *identical* to the state it was baked for. Validity is
 * checked every frame against a [Signature] that includes the identity of every drawn bitmap, so
 * a reloaded tile, a changed zoom/pan/size or a different scaler can never show a stale overlay.
 *
 * Everything here runs on the UI thread except the renderer's optional warm-up.
 */
internal class SettledScaler(
    private val view: View,
    private val host: Host,
) {

    internal interface Host {
        /** `true` while a gesture or animation is in progress (baking now would be wasted work). */
        fun isBusy(): Boolean

        /**
         * Fills [out] with the current drawn state. Returns `false` when the state is not eligible
         * for a settled overlay (not ready, rotated, not magnified, tiles missing, ...).
         */
        fun fillSignature(out: Signature): Boolean

        /** Lists every bitmap currently drawn, in view coordinates. */
        fun collectItems(out: MutableList<BicubicRenderer.Item>): Boolean

        /** The renderer failed repeatedly: stop trying until the scaler setting changes. */
        fun onPermanentFailure()
    }

    /** Everything the overlay's content depends on. Compared field by field, allocation-free. */
    internal class Signature {
        var scaler: ImageScaler = ImageScaler.DEFAULT
        var scale = 0f
        var translateX = 0f
        var translateY = 0f
        var viewWidth = 0
        var viewHeight = 0
        val visible = Rect()
        var contentHash = 0

        fun copyFrom(o: Signature) {
            scaler = o.scaler
            scale = o.scale
            translateX = o.translateX
            translateY = o.translateY
            viewWidth = o.viewWidth
            viewHeight = o.viewHeight
            visible.set(o.visible)
            contentHash = o.contentHash
        }

        fun sameAs(o: Signature): Boolean =
            scaler == o.scaler && scale == o.scale && translateX == o.translateX &&
                translateY == o.translateY && viewWidth == o.viewWidth && viewHeight == o.viewHeight &&
                visible == o.visible && contentHash == o.contentHash
    }

    private val current = Signature()
    private val baked = Signature()
    private val failed = Signature()
    private var hasBaked = false
    private var hasFailed = false
    private var failureCount = 0
    private var busyRetries = 0
    private var overlay: Bitmap? = null

    // Plain SRC_OVER on purpose: the overlay is transparent outside the image, and a SRC blend
    // mode would punch those transparent pixels through whatever the parent drew behind the view.
    // (Fully opaque page pixels simply replace the bilinear ones below them.)
    private val paint = Paint().apply { isFilterBitmap = true } // identity at integer offsets; smooth if a parent scales the view
    private val settleRunnable = Runnable { settle() }

    /** Called from `onDraw` after the normal content was drawn. */
    fun onDraw(canvas: Canvas, colorFilter: ColorFilter?) {
        if (!host.fillSignature(current)) {
            view.removeCallbacks(settleRunnable)
            return
        }
        val bmp = overlay
        if (hasBaked && bmp != null && !bmp.isRecycled && current.sameAs(baked)) {
            paint.colorFilter = colorFilter
            canvas.drawBitmap(bmp, baked.visible.left.toFloat(), baked.visible.top.toFloat(), paint)
        } else if (!(hasFailed && current.sameAs(failed))) {
            schedule()
        }
    }

    fun release() {
        view.removeCallbacks(settleRunnable)
        overlay?.recycle()
        overlay = null
        hasBaked = false
        hasFailed = false
        failureCount = 0
        busyRetries = 0
    }

    private fun schedule() {
        // Cheap (volatile read) once initialised; gives the GL context time to come up during the delay.
        BicubicRenderer.instance.warmUpAsync()
        view.removeCallbacks(settleRunnable)
        view.postDelayed(settleRunnable, SETTLE_DELAY_MS)
    }

    private fun settle() {
        if (!view.isAttachedToWindow) return
        if (host.isBusy()) {
            schedule()
            return
        }
        val sig = current // refreshed by fillSignature() on the next line
        if (!host.fillSignature(sig)) return
        if (hasBaked && sig.sameAs(baked)) return
        if (hasFailed && sig.sameAs(failed)) return

        val items = ArrayList<BicubicRenderer.Item>(8)
        if (!host.collectItems(items) || items.isEmpty()) return

        val w = sig.visible.width()
        val h = sig.visible.height()
        val target = obtainOverlay(w, h) ?: run {
            markFailed(sig)
            return
        }
        val mode = if (sig.scaler == ImageScaler.BSPLINE) 1 else 0
        when (
            BicubicRenderer.instance.render(items, sig.visible.left, sig.visible.top, w, h, mode, target)
        ) {
            BicubicRenderer.Result.DONE -> {
                baked.copyFrom(sig)
                hasBaked = true
                busyRetries = 0
                failureCount = 0
                target.prepareToDraw()
                view.invalidate()
            }
            BicubicRenderer.Result.BUSY -> {
                // Warm-up (or another view) holds the GL context: try again shortly, but not forever.
                if (++busyRetries <= MAX_BUSY_RETRIES) schedule() else markFailed(sig)
            }
            BicubicRenderer.Result.FAILED -> {
                hasBaked = false
                markFailed(sig)
            }
        }
    }

    private fun markFailed(sig: Signature) {
        failed.copyFrom(sig)
        hasFailed = true
        busyRetries = 0
        if (++failureCount >= MAX_FAILURES) host.onPermanentFailure()
    }

    private fun obtainOverlay(w: Int, h: Int): Bitmap? {
        val existing = overlay
        if (existing != null && !existing.isRecycled && existing.width == w && existing.height == h) return existing
        existing?.recycle()
        overlay = null
        hasBaked = false
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { overlay = it }
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private companion object {
        const val SETTLE_DELAY_MS = 140L
        const val MAX_BUSY_RETRIES = 20
        const val MAX_FAILURES = 3
    }
}
