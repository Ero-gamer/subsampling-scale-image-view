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
import com.davemorrissey.labs.subscaleview.ImageDownscaler
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

/** Returns a downscaler for [downscaler], or `null` for [ImageDownscaler.DEFAULT] / unsupported API levels. May throw. */
internal fun createBitmapDownscaler(downscaler: ImageDownscaler): BitmapScaler? {
	if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
	return createAgslDownscaler(downscaler)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun createAgslDownscaler(downscaler: ImageDownscaler): BitmapScaler? =
	when (downscaler) {
		ImageDownscaler.DEFAULT -> null
		ImageDownscaler.DPID -> AgslBicubicScaler(ScalerShaderSource.agslDpidShader(), needsTexSize = true)
	}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun createAgslScaler(scaler: ImageScaler): BitmapScaler? =
	when (scaler) {
		ImageScaler.DEFAULT -> {
			null
		}

		ImageScaler.CATMULL_ROM -> {
			AgslBicubicScaler(
				ScalerShaderSource.agslShader(ScalerShaderSource.CATMULL_ROM_B, ScalerShaderSource.CATMULL_ROM_C),
			)
		}

		ImageScaler.ROBIDOUX -> {
			AgslBicubicScaler(
				ScalerShaderSource.agslShader(ScalerShaderSource.ROBIDOUX_B, ScalerShaderSource.ROBIDOUX_C),
			)
		}

		ImageScaler.ROBIDOUX_SHARP -> {
			AgslBicubicScaler(
				ScalerShaderSource.agslShader(ScalerShaderSource.ROBIDOUX_SHARP_B, ScalerShaderSource.ROBIDOUX_SHARP_C),
			)
		}
	}

/**
 * Runs an AGSL resampling kernel (bicubic or DPID) over a [BitmapShader]. The kernels rely on hardware bilinear
 * filtering to fold several texels into one fetch, so the input shader is explicitly set to
 * linear filtering. One [RuntimeShader] instance is reused (its program is compiled once);
 * uniforms are updated and the shader re-assigned to the paint for every draw, which makes the
 * framework snapshot the uniform values per draw call.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AgslBicubicScaler(
	agsl: String,
	private val needsTexSize: Boolean = false,
) : BitmapScaler {
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
		val input =
			BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
				filterMode = BitmapShader.FILTER_MODE_LINEAR
			}
		shader.setInputShader("image", input)
		shader.setFloatUniform("dstOrigin", originX, originY)
		shader.setFloatUniform("invScale", 1f / scaleX, 1f / scaleY)
		if (needsTexSize) shader.setFloatUniform("texSize", bitmap.width.toFloat(), bitmap.height.toFloat())
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
