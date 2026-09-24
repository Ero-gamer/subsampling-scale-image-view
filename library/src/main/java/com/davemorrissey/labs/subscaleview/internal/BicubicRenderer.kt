package com.davemorrissey.labs.subscaleview.internal

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Off-screen EGL + GLES 3.0 renderer that resamples a set of already-decoded bitmaps (SSIV tiles
 * or the whole page) with an exact bicubic kernel into one viewport-sized [Bitmap].
 *
 * This is the scaler implementation for Android < 13, where AGSL runtime shaders do not exist.
 * It cannot run per frame (the GPU->CPU read-back costs tens of milliseconds on low-end GPUs), so
 * [SettledScaler] only invokes it once the view has stopped moving.
 *
 * ### Threading / lifecycle
 * - One process-wide instance ([instance]); every GL call happens under a single [ReentrantLock].
 * - The UI thread only ever [ReentrantLock.tryLock]s, so it can never block on a warm-up running on
 *   another thread; [Result.BUSY] is returned instead and the caller retries.
 * - EGL objects are created lazily and released automatically after [IDLE_RELEASE_MS] of inactivity,
 *   so a reader that stopped zooming does not keep a GL context and a viewport-sized pbuffer alive.
 * - The context is made current only for the duration of a call and released afterwards, so the
 *   warm-up thread and the UI thread can hand it over safely.
 *
 * ### Coordinate convention
 * Destination coordinates are top-down. Framebuffer row `r` is mapped to destination row `r`
 * (no vertical flip anywhere), so `glReadPixels` (row 0 first) is already in [Bitmap] row order.
 */
internal class BicubicRenderer private constructor() {

    enum class Result { DONE, BUSY, FAILED }

    /** One bitmap to draw: source pixel (0,0) lands on ([x],[y]); each source pixel covers [sx] x [sy] px. */
    class Item(val bitmap: Bitmap, val x: Float, val y: Float, val sx: Float, val sy: Float)

    private val lock = ReentrantLock()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val warmUpStarted = AtomicBoolean(false)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var maxTextureSize = 0

    private var program = 0
    private var uDstRect = -1
    private var uViewport = -1
    private var uTexture = -1
    private var uTexSize = -1
    private var uDstOrigin = -1
    private var uInvScale = -1
    private var uMode = -1

    private var pixelBuffer: ByteBuffer? = null

    // @Volatile: read without the lock by warmUpAsync() (called once per scheduled bake).
    @Volatile private var ready = false

    // Set when GL setup fails; the renderer then permanently reports FAILED instead of retrying
    // an expensive, doomed initialisation on every settle.
    @Volatile private var initFailed = false

    // eglInitialize/eglTerminate are reference counted per process by Android's libEGL, so only
    // terminate a display that THIS instance successfully initialised (other renderers, e.g.
    // GpuTileRenderer, share the default display).
    private var displayInitialized = false

    private val idleRelease = Runnable {
        if (lock.tryLock()) {
            try {
                releaseLocked()
            } finally {
                lock.unlock()
            }
        } else {
            scheduleIdleRelease()
        }
    }

    /** Starts one-time GL initialisation on a background thread so the first settle does not stall the UI. */
    fun warmUpAsync() {
        if (ready || initFailed) return
        if (!warmUpStarted.compareAndSet(false, true)) return
        Thread({
            lock.withLock {
                if (!ready && !initFailed) initLocked()
            }
            scheduleIdleRelease()
            warmUpStarted.set(false)
        }, "ssiv-bicubic-warmup").apply { isDaemon = true }.start()
    }

    /**
     * Renders [items] (coordinates in view space) into [target], which must be exactly [width] x
     * [height] and is overwritten completely. [originX]/[originY] is the view-space position of the
     * target's top-left pixel. [mode]: 0 = Catmull-Rom, 1 = B-Spline.
     */
    fun render(
        items: List<Item>,
        originX: Int,
        originY: Int,
        width: Int,
        height: Int,
        mode: Int,
        target: Bitmap,
    ): Result {
        if (!lock.tryLock()) return Result.BUSY
        try {
            if (initFailed) return Result.FAILED
            if (target.width != width || target.height != height || target.isRecycled) return Result.FAILED
            if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return Result.FAILED
            if (!ready && !initLocked()) return Result.FAILED
            if (width > maxTextureSize || height > maxTextureSize) return Result.FAILED
            for (item in items) {
                val b = item.bitmap
                if (b.isRecycled || b.width > maxTextureSize || b.height > maxTextureSize ||
                    b.config == Bitmap.Config.HARDWARE
                ) {
                    return Result.FAILED
                }
            }
            return renderLocked(items, originX, originY, width, height, mode, target)
        } catch (e: Throwable) {
            Log.w(TAG, "Bicubic render failed: $e")
            return Result.FAILED
        } finally {
            lock.unlock()
            scheduleIdleRelease()
        }
    }

    private fun renderLocked(
        items: List<Item>,
        originX: Int,
        originY: Int,
        width: Int,
        height: Int,
        mode: Int,
        target: Bitmap,
    ): Result {
        resizeSurfaceIfNeeded(width, height)
        makeCurrent()
        val tex = IntArray(1)
        try {
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { /* drain stale errors */ }
            GLES30.glViewport(0, 0, width, height)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            GLES30.glUseProgram(program)
            GLES30.glUniform2f(uViewport, width.toFloat(), height.toFloat())
            GLES30.glUniform1i(uMode, mode)
            GLES30.glUniform1i(uTexture, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

            for (item in items) {
                val bmp = item.bitmap
                val w = bmp.width
                val h = bmp.height
                val left = item.x - originX
                val top = item.y - originY

                GLES30.glGenTextures(1, tex, 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
                // Bilinear filtering is REQUIRED: the kernels fold texel pairs into single bilinear fetches.
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

                // Same conservative rule as GpuTileRenderer: only ARGB_8888 is uploaded directly.
                val upload = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
                try {
                    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, upload, 0)
                } finally {
                    if (upload !== bmp) upload.recycle()
                }

                GLES30.glUniform4f(uDstRect, left, top, left + w * item.sx, top + h * item.sy)
                GLES30.glUniform2f(uTexSize, w.toFloat(), h.toFloat())
                GLES30.glUniform2f(uDstOrigin, left, top)
                GLES30.glUniform2f(uInvScale, 1f / item.sx, 1f / item.sy)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

                GLES30.glDeleteTextures(1, tex, 0)
                tex[0] = 0
            }

            val total = width * height * 4
            var buf = pixelBuffer
            if (buf == null || buf.capacity() < total) {
                buf = ByteBuffer.allocateDirect(total).order(ByteOrder.nativeOrder())
                pixelBuffer = buf
            }
            buf!!.clear()
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            val error = GLES30.glGetError()
            if (error != GLES30.GL_NO_ERROR) {
                Log.w(TAG, "GL error 0x${error.toString(16)} while rendering; discarding result")
                return Result.FAILED
            }
            buf.position(0)
            target.copyPixelsFromBuffer(buf)
            return Result.DONE
        } finally {
            if (tex[0] != 0) GLES30.glDeleteTextures(1, tex, 0)
            releaseCurrent()
        }
    }

    // ── EGL / program setup ──────────────────────────────────────────────────

    private fun initLocked(): Boolean {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
            val versions = IntArray(2)
            check(EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) { "eglInitialize failed" }
            displayInitialized = true

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            // Prefer a config that advertises ES3; fall back to the ES2 bit (an ES3 context can
            // still be created from it on Android drivers), matching GpuTileRenderer.
            var found = EGL14.eglChooseConfig(
                eglDisplay, configAttribs(EGL14.EGL_OPENGL_ES2_BIT or EGLExt.EGL_OPENGL_ES3_BIT_KHR),
                0, configs, 0, 1, numConfigs, 0,
            ) && numConfigs[0] > 0
            if (!found) {
                found = EGL14.eglChooseConfig(
                    eglDisplay, configAttribs(EGL14.EGL_OPENGL_ES2_BIT),
                    0, configs, 0, 1, numConfigs, 0,
                ) && numConfigs[0] > 0
            }
            check(found) { "eglChooseConfig failed" }
            eglConfig = configs[0]!!

            eglContext = EGL14.eglCreateContext(
                eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext (ES 3.0) failed" }

            eglSurface = createPbuffer(1, 1)
            surfaceWidth = 1
            surfaceHeight = 1
            makeCurrent()

            val maxTex = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTex, 0)
            maxTextureSize = if (maxTex[0] > 0) maxTex[0] else 2048

            program = buildProgram()
            uDstRect = GLES30.glGetUniformLocation(program, "u_dstRect")
            uViewport = GLES30.glGetUniformLocation(program, "u_viewport")
            uTexture = GLES30.glGetUniformLocation(program, "u_texture")
            uTexSize = GLES30.glGetUniformLocation(program, "u_texSize")
            uDstOrigin = GLES30.glGetUniformLocation(program, "u_dstOrigin")
            uInvScale = GLES30.glGetUniformLocation(program, "u_invScale")
            uMode = GLES30.glGetUniformLocation(program, "u_mode")
            check(listOf(uDstRect, uViewport, uTexture, uTexSize, uDstOrigin, uInvScale, uMode).all { it >= 0 }) {
                "missing uniform location"
            }

            releaseCurrent()
            ready = true
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "Bicubic renderer init failed; scaler will fall back to the default: $e")
            initFailed = true
            releaseLocked()
            return false
        }
    }

    private fun configAttribs(renderableType: Int) = intArrayOf(
        EGL14.EGL_RENDERABLE_TYPE, renderableType,
        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
        EGL14.EGL_RED_SIZE, 8,
        EGL14.EGL_GREEN_SIZE, 8,
        EGL14.EGL_BLUE_SIZE, 8,
        EGL14.EGL_ALPHA_SIZE, 8,
        EGL14.EGL_DEPTH_SIZE, 0,
        EGL14.EGL_NONE,
    )

    private fun createPbuffer(w: Int, h: Int): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE)
        val surface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, attribs, 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface(${w}x$h) failed" }
        return surface
    }

    private fun resizeSurfaceIfNeeded(w: Int, h: Int) {
        if (w <= surfaceWidth && h <= surfaceHeight) return
        val newW = maxOf(w, surfaceWidth)
        val newH = maxOf(h, surfaceHeight)
        // Create before destroying: a failed creation must not leave a dangling surface handle.
        val newSurface = createPbuffer(newW, newH)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        eglSurface = newSurface
        surfaceWidth = newW
        surfaceHeight = newH
    }

    private fun makeCurrent() {
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed: 0x${EGL14.eglGetError().toString(16)}"
        }
    }

    private fun releaseCurrent() {
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    }

    private fun releaseLocked() {
        ready = false
        pixelBuffer = null
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        if (eglContext != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE && program != 0) {
            try {
                makeCurrent()
                GLES30.glDeleteProgram(program)
            } catch (_: Throwable) {
            }
        }
        program = 0
        releaseCurrent()
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        if (displayInitialized) EGL14.eglTerminate(eglDisplay)
        displayInitialized = false
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglConfig = null
        surfaceWidth = 0
        surfaceHeight = 0
        maxTextureSize = 0
    }

    private fun scheduleIdleRelease() {
        mainHandler.removeCallbacks(idleRelease)
        mainHandler.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    private fun buildProgram(): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SRC)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SRC)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        check(status[0] == GLES30.GL_TRUE) { "Program link failed: ${GLES30.glGetProgramInfoLog(prog)}" }
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { "Shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}" }
        return shader
    }

    companion object {
        private const val TAG = "SSIV.Bicubic"

        /** GL resources are dropped after this long without a render. */
        private const val IDLE_RELEASE_MS = 20_000L

        /** Upper bound of the viewport bitmap (24 MB at 4 bytes per pixel). */
        private const val MAX_PIXELS = 6_000_000L

        val instance: BicubicRenderer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { BicubicRenderer() }

        // Attribute-less quad from gl_VertexID (GLES 3.0): 0:(0,0) 1:(1,0) 2:(0,1) 3:(1,1) as a
        // triangle strip. u_dstRect is (left, top, right, bottom) in top-down viewport pixels, and
        // NDC y = -1 corresponds to destination row 0 (see the class comment: no vertical flip).
        private const val VERTEX_SRC = """#version 300 es
uniform vec4 u_dstRect;
uniform vec2 u_viewport;
void main() {
    vec2 c = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    vec2 p = mix(u_dstRect.xy, u_dstRect.zw, c);
    gl_Position = vec4(p / u_viewport * 2.0 - 1.0, 0.0, 1.0);
}
"""

        // Both kernels are exact and verified numerically against a direct 16-texel evaluation.
        // The evaluation point is the destination pixel centre mapped into source pixel space
        // (texel i has its centre at i + 0.5). Coordinates are highp: mediump would be off by
        // whole texels on large tiles.
        private const val FRAGMENT_SRC = """#version 300 es
precision highp float;
precision highp sampler2D;

uniform sampler2D u_texture;
uniform vec2 u_texSize;
uniform vec2 u_dstOrigin;
uniform vec2 u_invScale;
uniform int u_mode;   // 0 = Catmull-Rom (9 taps), 1 = B-Spline (4 taps)
out vec4 fragColor;

vec4 fetch(vec2 p) {
    return texture(u_texture, p / u_texSize);
}

vec4 catmullRom9(vec2 samplePos) {
    vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
    vec2 f = samplePos - texPos1;

    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);

    vec2 w12 = w1 + w2;
    vec2 p0 = texPos1 - 1.0;
    vec2 p3 = texPos1 + 2.0;
    vec2 p12 = texPos1 + w2 / w12;

    vec4 r = vec4(0.0);
    r += fetch(vec2(p0.x,  p0.y )) * (w0.x  * w0.y );
    r += fetch(vec2(p12.x, p0.y )) * (w12.x * w0.y );
    r += fetch(vec2(p3.x,  p0.y )) * (w3.x  * w0.y );
    r += fetch(vec2(p0.x,  p12.y)) * (w0.x  * w12.y);
    r += fetch(vec2(p12.x, p12.y)) * (w12.x * w12.y);
    r += fetch(vec2(p3.x,  p12.y)) * (w3.x  * w12.y);
    r += fetch(vec2(p0.x,  p3.y )) * (w0.x  * w3.y );
    r += fetch(vec2(p12.x, p3.y )) * (w12.x * w3.y );
    r += fetch(vec2(p3.x,  p3.y )) * (w3.x  * w3.y );
    return r;
}

vec4 bSpline4(vec2 samplePos) {
    vec2 coord = samplePos - 0.5;
    vec2 idx = floor(coord);
    vec2 f = coord - idx;
    vec2 f2 = f * f;
    vec2 f3 = f2 * f;

    vec2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
    vec2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
    vec2 w2 = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0;
    vec2 w3 = f3 / 6.0;

    vec2 g0 = w0 + w1;
    vec2 g1 = w2 + w3;
    vec2 p0 = idx + 0.5 + (w1 / g0 - 1.0);
    vec2 p1 = idx + 0.5 + (w3 / g1 + 1.0);

    vec4 t00 = fetch(vec2(p0.x, p0.y));
    vec4 t10 = fetch(vec2(p1.x, p0.y));
    vec4 t01 = fetch(vec2(p0.x, p1.y));
    vec4 t11 = fetch(vec2(p1.x, p1.y));
    return g0.y * (g0.x * t00 + g1.x * t10) + g1.y * (g0.x * t01 + g1.x * t11);
}

void main() {
    vec2 samplePos = (gl_FragCoord.xy - u_dstOrigin) * u_invScale;
    vec4 r = (u_mode == 0) ? catmullRom9(samplePos) : bSpline4(samplePos);
    // Negative Catmull-Rom lobes can overshoot; keep a valid premultiplied colour.
    r = clamp(r, 0.0, 1.0);
    r.rgb = min(r.rgb, r.aaa);
    fragColor = r;
}
"""
    }
}
