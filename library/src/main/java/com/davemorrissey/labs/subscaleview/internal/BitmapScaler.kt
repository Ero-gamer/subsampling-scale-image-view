package com.davemorrissey.labs.subscaleview.internal

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import com.davemorrissey.labs.subscaleview.ImageScaler

/** Draws a bitmap magnified with a custom resampling kernel. */
internal interface BitmapScaler {
    /**
     * Draws [bitmap] so that source pixel (0,0) lands on ([originX], [originY]) and every source
     * pixel covers [scaleX] x [scaleY] destination pixels (both > 1: magnification only).
     */
    fun draw(
        canvas: Canvas,
        bitmap: Bitmap,
        originX: Float,
        originY: Float,
        scaleX: Float,
        scaleY: Float,
        colorFilter: ColorFilter?,
    )
}

/** Returns a scaler for [scaler], or `null` for [ImageScaler.DEFAULT] / unsupported API levels. May throw. */
internal fun createBitmapScaler(scaler: ImageScaler): BitmapScaler? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return createAgslScaler(scaler)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun createAgslScaler(scaler: ImageScaler): BitmapScaler? = when (scaler) {
    ImageScaler.DEFAULT -> null
    ImageScaler.CATMULL_ROM -> AgslBicubicScaler(CATMULL_ROM_AGSL)
    ImageScaler.BSPLINE -> AgslBicubicScaler(BSPLINE_AGSL)
}

/**
 * Runs an AGSL bicubic kernel over a [BitmapShader]. The kernels rely on hardware bilinear
 * filtering to fold several texels into one fetch, so the input shader is explicitly set to
 * linear filtering. One [RuntimeShader] instance is reused (its program is compiled once);
 * uniforms are updated and the shader re-assigned to the paint for every draw, which makes the
 * framework snapshot the uniform values per draw call.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AgslBicubicScaler(agsl: String) : BitmapScaler {

    private val shader = RuntimeShader(agsl) // throws IllegalArgumentException on an AGSL error
    private val paint = Paint().apply { isAntiAlias = true }

    override fun draw(
        canvas: Canvas,
        bitmap: Bitmap,
        originX: Float,
        originY: Float,
        scaleX: Float,
        scaleY: Float,
        colorFilter: ColorFilter?,
    ) {
        // A fresh BitmapShader per draw on purpose: caching one would keep a recycled tile's
        // pixels alive (the native shader shares the bitmap's pixel storage).
        val input = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
        shader.setInputShader("image", input)
        shader.setFloatUniform("dstOrigin", originX, originY)
        shader.setFloatUniform("invScale", 1f / scaleX, 1f / scaleY)
        paint.shader = shader
        paint.colorFilter = colorFilter
        canvas.drawRect(
            originX,
            originY,
            originX + bitmap.width * scaleX,
            originY + bitmap.height * scaleY,
            paint,
        )
        paint.shader = null
    }
}

// Both kernels evaluate at the destination pixel centre `fragCoord`, mapped into source pixel
// space (texel i has its centre at i + 0.5, the coordinate space BitmapShader.eval uses).
// Weights are exact; see the numeric verification notes in the project history.

/**
 * Exact Catmull-Rom (B=0, C=0.5): 16 texels via 3x3 = 9 bilinear fetches. The outer taps keep
 * their negative weights (w0, w3 <= 0), which is what gives it its sharpness; that is also why
 * it cannot be collapsed to 4 fetches.
 */
private const val CATMULL_ROM_AGSL = """
uniform shader image;
uniform float2 dstOrigin;
uniform float2 invScale;

half4 main(float2 fragCoord) {
    float2 samplePos = (fragCoord - dstOrigin) * invScale;
    float2 texPos1 = floor(samplePos - 0.5) + 0.5;
    float2 f = samplePos - texPos1;

    float2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    float2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    float2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    float2 w3 = f * f * (-0.5 + 0.5 * f);

    float2 w12 = w1 + w2;
    float2 p0 = texPos1 - 1.0;
    float2 p3 = texPos1 + 2.0;
    float2 p12 = texPos1 + w2 / w12;

    float4 r = float4(0.0);
    r += float4(image.eval(float2(p0.x,  p0.y ))) * (w0.x  * w0.y );
    r += float4(image.eval(float2(p12.x, p0.y ))) * (w12.x * w0.y );
    r += float4(image.eval(float2(p3.x,  p0.y ))) * (w3.x  * w0.y );
    r += float4(image.eval(float2(p0.x,  p12.y))) * (w0.x  * w12.y);
    r += float4(image.eval(float2(p12.x, p12.y))) * (w12.x * w12.y);
    r += float4(image.eval(float2(p3.x,  p12.y))) * (w3.x  * w12.y);
    r += float4(image.eval(float2(p0.x,  p3.y ))) * (w0.x  * w3.y );
    r += float4(image.eval(float2(p12.x, p3.y ))) * (w12.x * w3.y );
    r += float4(image.eval(float2(p3.x,  p3.y ))) * (w3.x  * w3.y );

    // Negative lobes can overshoot; keep the result a valid premultiplied colour.
    r = clamp(r, 0.0, 1.0);
    r.rgb = min(r.rgb, r.aaa);
    return half4(r);
}
"""

/**
 * Cubic B-Spline (B=1, C=0): 16 texels via 2x2 = 4 bilinear fetches (adjacent texel pairs are
 * folded into one bilinear sample per axis). All weights are positive; the kernel smooths.
 */
private const val BSPLINE_AGSL = """
uniform shader image;
uniform float2 dstOrigin;
uniform float2 invScale;

half4 main(float2 fragCoord) {
    float2 samplePos = (fragCoord - dstOrigin) * invScale;
    float2 coord = samplePos - 0.5;
    float2 idx = floor(coord);
    float2 f = coord - idx;
    float2 f2 = f * f;
    float2 f3 = f2 * f;

    float2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
    float2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
    float2 w2 = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0;
    float2 w3 = f3 / 6.0;

    float2 g0 = w0 + w1;
    float2 g1 = w2 + w3;
    float2 p0 = idx + 0.5 + (w1 / g0 - 1.0);
    float2 p1 = idx + 0.5 + (w3 / g1 + 1.0);

    float4 t00 = float4(image.eval(float2(p0.x, p0.y)));
    float4 t10 = float4(image.eval(float2(p1.x, p0.y)));
    float4 t01 = float4(image.eval(float2(p0.x, p1.y)));
    float4 t11 = float4(image.eval(float2(p1.x, p1.y)));

    float4 r = g0.y * (g0.x * t00 + g1.x * t10) + g1.y * (g0.x * t01 + g1.x * t11);
    return half4(r);
}
"""
