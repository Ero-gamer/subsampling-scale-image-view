package com.davemorrissey.labs.subscaleview.decoder

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Off-screen EGL + GLES 3.0 renderer that applies [manga_enhance.frag] to every decoded tile
 * Bitmap in a single pass, replacing the removed CPU filter loops.
 *
 * ### Why EGL pbuffer instead of TextureView?
 * TextureView requires a live window surface and choreographer-driven render thread —
 * unsuitable for background tile post-processing. An EGL pbuffer is purely off-screen:
 * no display dependency, integrates with SSIV's Bitmap→Canvas tile pipeline without
 * touching pan/zoom or gesture logic, and avoids SurfaceTexture latency.
 *
 * ### Thread safety
 * A single [ReentrantLock] serialises all GL calls.
 *
 * ### Lifecycle
 * Call [release] when the containing [GpuFilteringDecoder] is recycled.
 *
 * ### Visibility
 * Public (not `internal`) because app code that embeds SSIV constructs and configures
 * this class directly (see [GpuFilteringDecoder.Factory] and [ReaderSettings] in the
 * consuming app), which lives in a separate Gradle module from this library.
 */
public class GpuTileRenderer(private val context: Context) {

    // ── EGL state ─────────────────────────────────────────────────────────────

    private val lock = ReentrantLock()

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext  = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface  = EGL14.EGL_NO_SURFACE
    // Cached config — avoids repeated eglChooseConfig on every pbuffer resize.
    private var cachedConfig: EGLConfig? = null
    private var surfaceWidth  = 0
    private var surfaceHeight = 0
    // GL_MAX_TEXTURE_SIZE of this device; images larger than this are returned unfiltered.
    private var maxTextureSize = Int.MAX_VALUE
    // Set by release(); a released renderer never re-initialises itself (in-flight decodes
    // that still hold a reference would otherwise leak a fresh EGL context).
    @Volatile private var isReleased = false

    private var program    = 0
    private var quadVbo    = 0
    private var quadVao    = 0

    private var uTexture    = -1
    private var uTexelSize  = -1
    private var uDenoise    = -1
    private var uDarken     = -1
    private var uVibrance   = -1
    private var uDenoiseStrength   = -1
    private var uVibranceIntensity = -1
    private var uRcasUsm             = -1
    private var uRcasUsmIntensity    = -1
    private var uAdaptiveSmoothstep          = -1
    private var uAdaptiveSmoothstepIntensity = -1
    private var uAdaptiveSigmoid          = -1
    private var uAdaptiveSigmoidIntensity = -1

    // Every filter is an independent (enable, intensity) pair — none is mutually exclusive.
    @Volatile var enableDenoise  = false
    @Volatile var enableDarken   = false
    @Volatile var enableVibrance = false
    /** 3x3 luma-weighted denoise falloff, 0.0 (mild) .. 1.0 (aggressive). Only used when [enableDenoise]. */
    @Volatile var denoiseStrength   = 0.5f
    /** Vibrance boost magnitude, 0.0 (no-op) .. 1.0 (full). Only used when [enableVibrance]. */
    @Volatile var vibranceIntensity = 1f
    /** Sharpen: RCAS-style clamp + unsharp mask. */
    @Volatile var enableRcasUsm = false
    @Volatile var rcasUsmIntensity = 0f
    /** Sharpen: adaptive, smoothstep (cubic Hermite) edge weight. */
    @Volatile var enableAdaptiveSmoothstep = false
    @Volatile var adaptiveSmoothstepIntensity = 0f
    /** Sharpen: adaptive, true logistic-sigmoid edge weight. */
    @Volatile var enableAdaptiveSigmoid = false
    @Volatile var adaptiveSigmoidIntensity = 0f

    private fun hasActiveFilter(): Boolean =
        enableDenoise || enableDarken || enableVibrance || enableRcasUsm ||
            enableAdaptiveSmoothstep || enableAdaptiveSigmoid

    private var ready = false

    // ── Public API ────────────────────────────────────────────────────────────

    fun init(): Boolean = lock.withLock {
        isReleased = false
        initLocked()
    }

    fun applyFilter(bitmap: Bitmap): Bitmap {
        if (isReleased) return bitmap
        if (!hasActiveFilter()) return bitmap
        return lock.withLock {
            if (isReleased) return@withLock bitmap
            if (!ready && !initLocked()) return@withLock bitmap
            applyFilterLocked(bitmap)
        }
    }

    fun release() = lock.withLock {
        isReleased = true
        releaseLocked()
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    private fun initLocked(): Boolean {
        if (ready) return true
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

            val versions = IntArray(2)
            check(EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) { "eglInitialize failed" }

            val configs    = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            check(
                EGL14.eglChooseConfig(eglDisplay, CONFIG_ATTRIBS, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0
            ) { "eglChooseConfig failed" }
            cachedConfig = configs[0]!!

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, cachedConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

            eglSurface    = createPbuffer(1, 1)
            surfaceWidth  = 1
            surfaceHeight = 1

            makeCurrent()

            val maxTex = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTex, 0)
            maxTextureSize = if (maxTex[0] > 0) maxTex[0] else 2048

            program = buildProgram(loadShaderSource())
            quadVbo = buildQuadVbo()
            quadVao = buildQuadVao(quadVbo)

            uTexture     = GLES30.glGetUniformLocation(program, "u_texture")
            uTexelSize   = GLES30.glGetUniformLocation(program, "u_texelSize")
            uDenoise     = GLES30.glGetUniformLocation(program, "u_enableDenoise")
            uDarken      = GLES30.glGetUniformLocation(program, "u_enableDarken")
            uVibrance    = GLES30.glGetUniformLocation(program, "u_enableVibrance")
            uDenoiseStrength   = GLES30.glGetUniformLocation(program, "u_denoiseStrength")
            uVibranceIntensity = GLES30.glGetUniformLocation(program, "u_vibranceIntensity")
            uRcasUsm          = GLES30.glGetUniformLocation(program, "u_enableRcasUsm")
            uRcasUsmIntensity = GLES30.glGetUniformLocation(program, "u_rcasUsmIntensity")
            uAdaptiveSmoothstep          = GLES30.glGetUniformLocation(program, "u_enableAdaptiveSmoothstep")
            uAdaptiveSmoothstepIntensity = GLES30.glGetUniformLocation(program, "u_adaptiveSmoothstepIntensity")
            uAdaptiveSigmoid          = GLES30.glGetUniformLocation(program, "u_enableAdaptiveSigmoid")
            uAdaptiveSigmoidIntensity = GLES30.glGetUniformLocation(program, "u_adaptiveSigmoidIntensity")

            releaseCurrent()
            ready = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "GpuTileRenderer init failed — falling back to unfiltered tiles", e)
            releaseLocked()
            return false
        }
    }

    private fun loadShaderSource(): String =
        context.assets.open("manga_enhance.frag").bufferedReader().use { it.readText() }

    private fun buildProgram(fragSrc: String): Int {
        val vs   = compileShader(GLES30.GL_VERTEX_SHADER,   VERTEX_SRC)
        val fs   = compileShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glBindAttribLocation(prog, 0, "a_position")
        GLES30.glBindAttribLocation(prog, 1, "a_texCoord")
        GLES30.glLinkProgram(prog)
        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        check(status[0] == GLES30.GL_TRUE) {
            "Program link failed: ${GLES30.glGetProgramInfoLog(prog)}"
        }
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) {
            "Shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    /** Full-screen quad: (position.xy, texCoord.xy) interleaved, TRIANGLE_STRIP winding. */
    private fun buildQuadVbo(): Int {
        val data: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(
                    -1f, -1f,  0f, 0f,
                     1f, -1f,  1f, 0f,
                    -1f,  1f,  0f, 1f,
                     1f,  1f,  1f, 1f,
                ))
                position(0)
            }
        val vbo = IntArray(1)
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 4 * 4 * 4, data, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        return vbo[0]
    }

    private fun buildQuadVao(vbo: Int): Int {
        val vao    = IntArray(1)
        val stride = 4 * 4
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 2 * 4)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        return vao[0]
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private fun applyFilterLocked(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        // Too large for this GPU: return unfiltered rather than failing the decode.
        if (w > maxTextureSize || h > maxTextureSize) return src

        resizeSurfaceIfNeeded(w, h)
        makeCurrent()

        val texIds = IntArray(1)
        try {
            // Upload source bitmap to GL texture
            GLES30.glGenTextures(1, texIds, 0)
            val texId = texIds[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            // GLUtils.texImage2D requires ARGB_8888
            val upload = if (src.config == Bitmap.Config.ARGB_8888) src
                         else src.copy(Bitmap.Config.ARGB_8888, false)
            try {
                android.opengl.GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, upload, 0)
            } finally {
                if (upload !== src) upload.recycle()
            }

            // Draw full-screen quad through shader
            GLES30.glViewport(0, 0, w, h)
            GLES30.glUseProgram(program)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
            GLES30.glUniform1i(uTexture,     0)
            GLES30.glUniform2f(uTexelSize,   1f / w, 1f / h)
            GLES30.glUniform1i(uDenoise,     if (enableDenoise)  1 else 0)
            GLES30.glUniform1i(uDarken,      if (enableDarken)   1 else 0)
            GLES30.glUniform1i(uVibrance,    if (enableVibrance) 1 else 0)
            GLES30.glUniform1f(uDenoiseStrength,   denoiseStrength)
            GLES30.glUniform1f(uVibranceIntensity, vibranceIntensity)
            GLES30.glUniform1i(uRcasUsm,          if (enableRcasUsm) 1 else 0)
            GLES30.glUniform1f(uRcasUsmIntensity, rcasUsmIntensity)
            GLES30.glUniform1i(uAdaptiveSmoothstep,          if (enableAdaptiveSmoothstep) 1 else 0)
            GLES30.glUniform1f(uAdaptiveSmoothstepIntensity, adaptiveSmoothstepIntensity)
            GLES30.glUniform1i(uAdaptiveSigmoid,          if (enableAdaptiveSigmoid) 1 else 0)
            GLES30.glUniform1f(uAdaptiveSigmoidIntensity, adaptiveSigmoidIntensity)
            GLES30.glBindVertexArray(quadVao)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindVertexArray(0)

            // Read back. The quad's texcoord mapping (NDC y=-1 -> v=0) combined with
            // GLUtils.texImage2D's top-down bitmap upload renders the tile flipped once into
            // the framebuffer; glReadPixels' bottom-up readback convention exactly cancels
            // that flip. The result is that this buffer is ALREADY in correct top-down row
            // order for Bitmap.copyPixelsFromBuffer — do not flip it again (a previous
            // version of this code did, which produced upside-down tiles).
            val totalBytes = w * h * 4
            val pixelBuf   = ByteBuffer.allocateDirect(totalBytes).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixelBuf)
            pixelBuf.position(0)

            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            result.copyPixelsFromBuffer(pixelBuf)
            return result
        } finally {
            // Always free the texture and detach the context, even on failure — otherwise the
            // context stays current on this thread and every later call from another thread
            // would fail in eglMakeCurrent.
            if (texIds[0] != 0) GLES30.glDeleteTextures(1, texIds, 0)
            releaseCurrent()
        }
    }

    // ── EGL helpers ───────────────────────────────────────────────────────────

    private fun createPbuffer(w: Int, h: Int): EGLSurface {
        val attrs = intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE)
        val s = EGL14.eglCreatePbufferSurface(eglDisplay, cachedConfig, attrs, 0)
        check(s != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface($w,$h) failed" }
        return s
    }

    /** Destroys and recreates the pbuffer only when the tile is larger than the current surface. */
    private fun resizeSurfaceIfNeeded(w: Int, h: Int) {
        if (w <= surfaceWidth && h <= surfaceHeight) return
        // Grow by max of both dimensions so e.g. a tall-then-wide sequence doesn't thrash.
        val newW = maxOf(w, surfaceWidth)
        val newH = maxOf(h, surfaceHeight)
        // Create the new pbuffer BEFORE destroying the old one: if creation fails (large
        // image), the renderer keeps a valid surface instead of a dangling destroyed handle.
        val newSurface = createPbuffer(newW, newH)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        eglSurface    = newSurface
        surfaceWidth  = newW
        surfaceHeight = newH
    }

    private fun makeCurrent() {
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed: 0x${EGL14.eglGetError().toString(16)}"
        }
    }

    private fun releaseCurrent() {
        EGL14.eglMakeCurrent(
            eglDisplay,
            EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT,
        )
    }

    private fun releaseLocked() {
        ready = false
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        try { makeCurrent() } catch (_: Exception) {}
        if (program != 0) { GLES30.glDeleteProgram(program); program = 0 }
        if (quadVao != 0) { GLES30.glDeleteVertexArrays(1, intArrayOf(quadVao), 0); quadVao = 0 }
        if (quadVbo != 0) { GLES30.glDeleteBuffers(1,      intArrayOf(quadVbo), 0); quadVbo = 0 }
        releaseCurrent()
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
        eglSurface    = EGL14.EGL_NO_SURFACE
        eglContext    = EGL14.EGL_NO_CONTEXT
        eglDisplay    = EGL14.EGL_NO_DISPLAY
        cachedConfig  = null
        surfaceWidth  = 0
        surfaceHeight = 0
        maxTextureSize = Int.MAX_VALUE
    }

    companion object {
        private const val TAG = "GpuTileRenderer"

        // Shared across all instances — allocated once at class load time.
        private val CONFIG_ATTRIBS = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE,    EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE,    8,
            EGL14.EGL_GREEN_SIZE,  8,
            EGL14.EGL_BLUE_SIZE,   8,
            EGL14.EGL_ALPHA_SIZE,  8,
            EGL14.EGL_DEPTH_SIZE,  0,
            EGL14.EGL_NONE,
        )

        private val VERTEX_SRC = """
            #version 300 es
            in vec2 a_position;
            in vec2 a_texCoord;
            out vec2 v_texCoord;
            void main() {
                v_texCoord = a_texCoord;
                gl_Position = vec4(a_position, 0.0, 1.0);
            }
        """.trimIndent()
    }
}
