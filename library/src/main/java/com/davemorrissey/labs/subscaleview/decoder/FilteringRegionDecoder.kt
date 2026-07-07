package com.davemorrissey.labs.subscaleview.decoder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import androidx.annotation.WorkerThread
import com.davemorrissey.labs.subscaleview.ImageSource
import kotlin.math.abs

/**
 * [ImageRegionDecoder] wrapper applying CPU denoise→sharpen→vibrance→dither/grain
 * to every decoded tile in one getPixels/loop/setPixels pass.
 * Thread-local IntArray pools eliminate per-tile heap allocations.
 */
public class FilteringRegionDecoder(
    private val inner: ImageRegionDecoder,
    private val sharpening: Float,
    private val vibrance: Float,
    private val denoise: Float = 0f,
    private val dither: Float = 0f,
    private val grain: Float = 0f,
) : ImageRegionDecoder {

    override fun init(context: Context, uri: Uri): Point = inner.init(context, uri)
    override fun init(context: Context, source: ImageSource): Point = inner.init(context, source)

    @WorkerThread
    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        val tile = inner.decodeRegion(sRect, sampleSize)
        // Wrap in try/catch: SSIV swallows Throwable from decodeRegion() into a no-op
        // onTileLoadError, permanently stranding an unfiltered tile. Return the unfiltered
        // tile on failure so the image stays readable rather than silently broken.
        return try { applyFilters(tile) } catch (e: Throwable) { tile }
    }

    override val isReady: Boolean get() = inner.isReady
    override fun recycle() = inner.recycle()

    private fun applyFilters(tile: Bitmap): Bitmap {
        val doSharpen  = sharpening > 0.01f
        val doVibrance = vibrance != 0f
        val doDenoise  = denoise > 0.01f && doSharpen
        val doDither   = dither > 0.01f
        val doGrain    = grain > 0.01f
        if (!doSharpen && !doVibrance && !doDither && !doGrain) return tile

        val w = tile.width; val h = tile.height
        if (w <= 0 || h <= 0) return tile

        val needsCopy = tile.config != Bitmap.Config.ARGB_8888 || !tile.isMutable
        // Guard: Bitmap.copy() CAN return null on OOM. Never recycle tile until confirmed.
        val working = if (needsCopy) tile.copy(Bitmap.Config.ARGB_8888, true) ?: tile else tile

        val src = srcPool.acquire(w * h)
        working.getPixels(src, 0, w, 0, 0, w, h)
        val k   = if (doSharpen) sharpening.coerceIn(0f, 1f) * SHARPEN_SCALAR else 0f
        val out = if (doSharpen) outPool.acquire(w * h) else src
        val dgt = if (doDither || doGrain) buildDitherGrainTable(dither, grain) else null

        for (y in 0 until h) {
            val row = y * w; val hasA = y > 0; val hasB = y < h - 1
            for (x in 0 until w) {
                val idx = row + x; val px = src[idx]
                var r: Int; var g: Int; var b: Int
                if (doSharpen && x > 0 && x < w - 1 && hasA && hasB) {
                    val top=src[idx-w]; val bot=src[idx+w]; val lft=src[idx-1]; val rgt=src[idx+1]
                    val cr=(px shr 16)and 0xFF; val cg=(px shr 8)and 0xFF; val cb=px and 0xFF
                    val tr=(top shr 16)and 0xFF;  val tg=(top shr 8)and 0xFF;  val tb=top and 0xFF
                    val brr=(bot shr 16)and 0xFF; val brg=(bot shr 8)and 0xFF; val brb=bot and 0xFF
                    val lr=(lft shr 16)and 0xFF;  val lg=(lft shr 8)and 0xFF;  val lb=lft and 0xFF
                    val rr=(rgt shr 16)and 0xFF;  val rg=(rgt shr 8)and 0xFF;  val rb=rgt and 0xFF
                    val dr=if(doDenoise) denoiseC(cr,tr,brr,lr,rr) else cr
                    val dg=if(doDenoise) denoiseC(cg,tg,brg,lg,rg) else cg
                    val db=if(doDenoise) denoiseC(cb,tb,brb,lb,rb) else cb
                    r=sharpenC(dr,tr,brr,lr,rr,k); g=sharpenC(dg,tg,brg,lg,rg,k); b=sharpenC(db,tb,brb,lb,rb,k)
                } else { r=(px shr 16)and 0xFF; g=(px shr 8)and 0xFF; b=px and 0xFF }

                if (doVibrance) {
                    val f = vibranceF(r,g,b,vibrance)
                    if (f != 1f) { val m=(r+g+b)/3f; r=cl(m+(r-m)*f); g=cl(m+(g-m)*f); b=cl(m+(b-m)*f) }
                }
                if (dgt != null) { val n=dgt[((y and 63) shl 6)or(x and 63)]; if(n!=0){r=(r+n).coerceIn(0,255);g=(g+n).coerceIn(0,255);b=(b+n).coerceIn(0,255)} }
                out[idx] = (px and ALPHA_MASK) or (r shl 16) or (g shl 8) or b
            }
        }
        working.setPixels(out, 0, w, 0, 0, w, h)
        // Recycle AFTER setPixels — ensures the catch-block fallback is still a live bitmap.
        if (needsCopy && working !== tile) tile.recycle()
        return working
    }

    private fun denoiseC(c: Int, t: Int, b: Int, l: Int, r: Int): Int {
        val wT=1f/(1f+abs(c-t)*DENOISE_FALLOFF); val wB=1f/(1f+abs(c-b)*DENOISE_FALLOFF)
        val wL=1f/(1f+abs(c-l)*DENOISE_FALLOFF); val wR=1f/(1f+abs(c-r)*DENOISE_FALLOFF)
        val m=(c+t*wT+b*wB+l*wL+r*wR)/(1f+wT+wB+wL+wR)
        val v=c+DENOISE_STRENGTH*(m-c)
        return when { v<=0f->0; v>=255f->255; else->(v+0.5f).toInt() }
    }

    private fun sharpenC(c: Int, t: Int, b: Int, l: Int, r: Int, k: Float): Int {
        val v=c+k*(4*c-t-b-l-r)
        return when { v<=0f->0; v>=255f->255; else->(v+0.5f).toInt() }
    }

    private fun vibranceF(r: Int, g: Int, b: Int, v: Float): Float {
        if (v==0f) return 1f
        val mx=maxOf(r,g,b); val mn=minOf(r,g,b); val d=mx-mn
        if (d==0) return 1f
        val ls=mx+mn; val denom=(if(ls<=255)ls else 510-ls).coerceAtLeast(1)
        val sat=d.toFloat()/denom; val sel=(1f-sat)*(1f-sat)
        return 1f+v.coerceIn(-1f,1f)*sel*VIBRANCE_SCALAR
    }

    private fun cl(v: Float): Int = when { v<=0f->0; v>=255f->255; else->(v+0.5f).toInt() }

    public class Factory(
        private val innerFactory: DecoderFactory<out ImageRegionDecoder>,
        val sharpening: Float,
        val vibrance: Float,
        val denoise: Float = 0f,
        val dither: Float = 0f,
        val grain: Float = 0f,
    ) : DecoderFactory<FilteringRegionDecoder> {
        override val bitmapConfig: Bitmap.Config? get() = innerFactory.bitmapConfig
        override fun make() = FilteringRegionDecoder(innerFactory.make(), sharpening, vibrance, denoise, dither, grain)
    }

    private companion object {
        private const val SHARPEN_SCALAR   = 0.5f
        private const val VIBRANCE_SCALAR  = 1.5f
        private const val DENOISE_STRENGTH = 0.6f
        private const val DENOISE_FALLOFF  = 0.15f
        private val ALPHA_MASK = 0xFF000000.toInt()

        private val srcPool = ThreadLocal<IntArray>()
        private val outPool = ThreadLocal<IntArray>()

        private fun ThreadLocal<IntArray>.acquire(n: Int): IntArray {
            val ex=get(); if (ex!=null&&ex.size>=n) return ex
            var cap=n.coerceAtLeast(1024); cap=Integer.highestOneBit(cap-1) shl 1
            return IntArray(cap).also{set(it)}
        }

        private fun buildDitherGrainTable(d: Float, g: Float): IntArray {
            val b8=intArrayOf(0,32,8,40,2,34,10,42,48,16,56,24,50,18,58,26,12,44,4,36,14,46,6,38,60,28,52,20,62,30,54,22,3,35,11,43,1,33,9,41,51,19,59,27,49,17,57,25,15,47,7,39,13,45,5,37,63,31,55,23,61,29,53,21)
            val rand=java.util.Random(0xC0FFEEL); val dM=d*3f; val gM=g*5f
            return IntArray(64*64){i->
                val x=i and 63; val y=i shr 6
                ((b8[(y%8)*8+(x%8)]/63f-0.5f)*dM+(rand.nextFloat()-0.5f)*gM).toInt().coerceIn(-8,8)
            }
        }
    }
}
