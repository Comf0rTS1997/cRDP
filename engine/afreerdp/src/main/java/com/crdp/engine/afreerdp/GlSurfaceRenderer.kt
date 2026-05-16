package com.crdp.engine.afreerdp

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.HandlerThread
import android.os.Handler
import android.util.Log
import android.view.Surface
import com.crdp.core.rdp.engine.SamplingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * EGL14 + GLES2 renderer that owns the destination [Surface] and samples a
 * [Bitmap] (the FreeRDP-painted framebuffer) via a single full-screen quad.
 *
 * All EGL / GL calls happen on a dedicated render thread; public methods are
 * thread-safe. The render thread serializes attach / detach / draw via a Handler.
 *
 * The renderer accepts the same [SamplingMode] options surfaced in user
 * settings: nearest (pixel-perfect), bilinear (default), lanczos3 (sharper at
 * a small ALU cost).
 *
 * Texture upload: `GLUtils.texSubImage2D` pulls bytes directly out of the
 * Bitmap's native pixel buffer in a single pass — no intermediate Java buffer.
 * Whole-bitmap upload per flush; dirty-rect uploads would need a Bitmap-backed
 * sub-region copy (or AHardwareBuffer mapping per Tier 2) and aren't worth the
 * extra complexity at this layer.
 */
internal class GlSurfaceRenderer {

    private val ht: HandlerThread = HandlerThread("crdp-gl-render").apply { start() }
    private val handler: Handler = Handler(ht.looper)

    @Volatile private var surface: Surface? = null
    @Volatile private var surfaceWidth: Int = 0
    @Volatile private var surfaceHeight: Int = 0
    @Volatile private var sampling: SamplingMode = SamplingMode.Bilinear

    @Volatile private var bitmap: Bitmap? = null

    // Dirty rect — single producer (engine worker), single consumer (render thread).
    // We accumulate on the worker; the render thread snapshots+resets under monitor.
    private val dirtyMonitor = Any()
    private var dirtyLeft = Int.MAX_VALUE
    private var dirtyTop = Int.MAX_VALUE
    private var dirtyRight = 0
    private var dirtyBottom = 0

    // Frame pacing.
    private var minFrameIntervalNanos: Long = 16_666_667L
    private var lastDrawNanos: Long = 0L

    // GL state — touched only on the render thread.
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    private var program: Int = 0
    private var aPosLoc: Int = -1
    private var aTexLoc: Int = -1
    private var uTexLoc: Int = -1
    private var uMatLoc: Int = -1
    private var uTexSizeLoc: Int = -1
    private var textureId: Int = 0
    private var texW: Int = 0
    private var texH: Int = 0
    private var quadBuf: ByteBuffer? = null
    private var loadedSampling: SamplingMode? = null

    /** Attach a destination [Surface]. Posted to the render thread. */
    fun attach(surface: Surface, width: Int, height: Int, sampling: SamplingMode, refreshHz: Float) {
        this.surface = surface
        this.surfaceWidth = width
        this.surfaceHeight = height
        this.sampling = sampling
        minFrameIntervalNanos = if (refreshHz > 0f) (1_000_000_000.0 / refreshHz).toLong() else 16_666_667L
        lastDrawNanos = 0L
        handler.post { initEglOnRenderThread() }
    }

    /** Detach the destination [Surface]. Posted to the render thread. */
    fun detach() {
        surface = null
        handler.post {
            // EGL surface is the only thing pinned to the destination Surface;
            // tear it down so the next attach() reinits cleanly. Display + GL
            // context survive across attaches.
            releaseEglSurface()
        }
    }

    /** Full teardown. Releases EGL + render thread. Idempotent. */
    fun release() {
        handler.post {
            releaseEglSurface()
            releaseEglContext()
            // Bitmap is borrowed — never recycle here. The blitter owns it.
            bitmap = null
        }
        ht.quitSafely()
    }

    /**
     * Adopt a Bitmap owned by the caller (the blitter). The renderer borrows
     * the reference; it never recycles. Whenever the size changes the texture
     * is rebuilt on the next draw.
     */
    fun adoptBitmap(bmp: Bitmap) {
        bitmap = bmp
    }

    fun currentBitmap(): Bitmap? = bitmap

    /** Worker-thread mark. Lock-free wrt the render thread; uses a tiny monitor only here. */
    fun markDirty(x: Int, y: Int, w: Int, h: Int) {
        synchronized(dirtyMonitor) {
            if (dirtyLeft > x) dirtyLeft = x
            if (dirtyTop > y) dirtyTop = y
            val r = x + w; if (dirtyRight < r) dirtyRight = r
            val b = y + h; if (dirtyBottom < b) dirtyBottom = b
        }
    }

    /**
     * Schedule a draw on the render thread if the per-frame interval has elapsed
     * and a dirty region is pending. Returns the union dirty rect that will be
     * uploaded ([x, y, w, h]) for caller frame metrics, or `null` if throttled
     * or nothing to do.
     */
    fun flushDirty(): IntArray? {
        val nowNanos = System.nanoTime()
        if (nowNanos - lastDrawNanos < minFrameIntervalNanos) return null

        val dx: Int; val dy: Int; val dw: Int; val dh: Int
        synchronized(dirtyMonitor) {
            if (dirtyRight <= dirtyLeft || dirtyBottom <= dirtyTop) return null
            dx = dirtyLeft; dy = dirtyTop
            dw = dirtyRight - dx; dh = dirtyBottom - dy
            dirtyLeft = Int.MAX_VALUE; dirtyTop = Int.MAX_VALUE
            dirtyRight = 0; dirtyBottom = 0
        }

        lastDrawNanos = nowNanos
        handler.post { drawOnRenderThread() }
        return intArrayOf(dx, dy, dw, dh)
    }

    /** Switch sampler at runtime. */
    fun setSampling(mode: SamplingMode) {
        if (sampling == mode) return
        sampling = mode
        handler.post {
            // Force shader rebuild on the next draw.
            loadedSampling = null
        }
    }

    // ────── render-thread internals ────────────────────────────────────────────

    private fun initEglOnRenderThread() {
        val s = surface ?: return
        if (!s.isValid) return
        try {
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                require(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
                val ver = IntArray(2)
                require(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "eglInitialize failed" }
            }
            if (eglConfig == null) {
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfig = IntArray(1)
                val attribs = intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE,
                )
                require(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfig, 0) && numConfig[0] > 0) {
                    "eglChooseConfig failed"
                }
                eglConfig = configs[0]
            }
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
                require(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            }
            // Recreate window surface if missing or surface changed.
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            val winAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, s, winAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, "eglCreateWindowSurface failed err=0x${EGL14.eglGetError().toString(16)}")
                return
            }
            require(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                "eglMakeCurrent failed"
            }
            ensureProgram()
            ensureTexture()
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        } catch (t: Throwable) {
            Log.e(TAG, "GL init failed", t)
            releaseEglSurface()
        }
    }

    private fun drawOnRenderThread() {
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            initEglOnRenderThread()
            if (eglSurface == EGL14.EGL_NO_SURFACE) return
        }
        val bmp = bitmap ?: return
        val sw = surfaceWidth
        val sh = surfaceHeight
        if (sw <= 0 || sh <= 0) return

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.w(TAG, "eglMakeCurrent failed in draw err=0x${EGL14.eglGetError().toString(16)}")
            return
        }

        if (loadedSampling != sampling) {
            ensureProgram()
        }
        ensureTexture()
        uploadBitmap(bmp)

        GLES20.glViewport(0, 0, sw, sh)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        // Letterbox: scale the [-1,1] quad to (outW/sw, outH/sh).
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val scale = min(sw / bw, sh / bh)
        val outW = bw * scale
        val outH = bh * scale
        val sx = outW / sw
        val sy = outH / sh
        val mat = floatArrayOf(
            sx, 0f, 0f, 0f,
            0f, sy, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        GLES20.glUniformMatrix4fv(uMatLoc, 1, false, mat, 0)
        if (uTexSizeLoc >= 0) {
            GLES20.glUniform2f(uTexSizeLoc, bw, bh)
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTexLoc, 0)

        val buf = quadBuf!!
        buf.position(0)
        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, buf)
        buf.position(2 * 4)
        GLES20.glEnableVertexAttribArray(aTexLoc)
        GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 16, buf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosLoc)
        GLES20.glDisableVertexAttribArray(aTexLoc)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun ensureProgram() {
        if (program != 0 && loadedSampling == sampling) return
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        val vsSrc = VS
        val fsSrc = when (sampling) {
            SamplingMode.Nearest -> FS_NEAREST
            SamplingMode.Bilinear -> FS_BILINEAR
            SamplingMode.Lanczos3 -> FS_LANCZOS3
        }
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
            error("Program link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        program = p
        aPosLoc = GLES20.glGetAttribLocation(p, "aPos")
        aTexLoc = GLES20.glGetAttribLocation(p, "aTex")
        uTexLoc = GLES20.glGetUniformLocation(p, "uTex")
        uMatLoc = GLES20.glGetUniformLocation(p, "uMat")
        uTexSizeLoc = GLES20.glGetUniformLocation(p, "uTexSize")
        loadedSampling = sampling

        if (quadBuf == null) {
            // x, y, u, v — TRIANGLE_STRIP. Flip V because Bitmap rows are top-down
            // but GL texcoord origin is bottom-left.
            val verts = floatArrayOf(
                -1f, -1f, 0f, 1f,
                 1f, -1f, 1f, 1f,
                -1f,  1f, 0f, 0f,
                 1f,  1f, 1f, 0f,
            )
            val bb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            bb.asFloatBuffer().put(verts)
            quadBuf = bb
        }
    }

    private fun ensureTexture() {
        val bmp = bitmap ?: return
        if (textureId == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            applySamplerTexParams()
            texW = 0; texH = 0
        }
        if (texW != bmp.width || texH != bmp.height) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            texW = bmp.width
            texH = bmp.height
        }
    }

    private fun uploadBitmap(bmp: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        if (texW != bmp.width || texH != bmp.height) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            texW = bmp.width; texH = bmp.height
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
        }
        applySamplerTexParams()
    }

    private fun applySamplerTexParams() {
        // Lanczos3 filters in shader, so leave GL filter at NEAREST to avoid double-blur.
        val (mag, mn) = when (sampling) {
            SamplingMode.Nearest -> GLES20.GL_NEAREST to GLES20.GL_NEAREST
            SamplingMode.Bilinear -> GLES20.GL_LINEAR to GLES20.GL_LINEAR
            SamplingMode.Lanczos3 -> GLES20.GL_NEAREST to GLES20.GL_NEAREST
        }
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, mag)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, mn)
    }

    private fun compileShader(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val status = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(sh)
            GLES20.glDeleteShader(sh)
            error("Shader compile failed: $log")
        }
        return sh
    }

    private fun releaseEglSurface() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    private fun releaseEglContext() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program); program = 0
            loadedSampling = null
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        EGL14.eglTerminate(eglDisplay)
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglConfig = null
    }

    private companion object {
        const val TAG = "GlSurfaceRenderer"

        const val VS = """
            attribute vec2 aPos;
            attribute vec2 aTex;
            uniform mat4 uMat;
            varying vec2 vTex;
            void main() {
                gl_Position = uMat * vec4(aPos, 0.0, 1.0);
                vTex = aTex;
            }
        """

        // alpha forced to 1.0 because FreeRDP writes PIXEL_FORMAT_RGBX32 — the X byte
        // is don't-care and would otherwise let SurfaceFlinger blend the buffer
        // against whatever's behind the SurfaceView.
        const val FS_NEAREST = """
            precision mediump float;
            uniform sampler2D uTex;
            varying vec2 vTex;
            void main() { gl_FragColor = vec4(texture2D(uTex, vTex).rgb, 1.0); }
        """

        const val FS_BILINEAR = """
            precision mediump float;
            uniform sampler2D uTex;
            varying vec2 vTex;
            void main() { gl_FragColor = vec4(texture2D(uTex, vTex).rgb, 1.0); }
        """

        // 3-tap separable Lanczos approximation. Driven from the integer pixel
        // grid so we get sharper-than-bilinear edges at fractional zoom.
        // Falls back to bilinear shape with sharper kernel weights; mediump to
        // keep mobile GPUs happy.
        const val FS_LANCZOS3 = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec2 uTexSize;
            varying vec2 vTex;

            float sinc(float x) {
                if (abs(x) < 1e-4) return 1.0;
                float pix = 3.14159265 * x;
                return sin(pix) / pix;
            }
            float lz(float x) {
                if (abs(x) >= 3.0) return 0.0;
                return sinc(x) * sinc(x / 3.0);
            }

            void main() {
                vec2 px = vTex * uTexSize;
                vec2 cf = floor(px - 0.5) + 0.5;
                vec2 f = px - cf;
                vec4 col = vec4(0.0);
                float wsum = 0.0;
                for (int dy = -2; dy <= 3; dy++) {
                    for (int dx = -2; dx <= 3; dx++) {
                        vec2 sp = (cf + vec2(float(dx), float(dy))) / uTexSize;
                        float w = lz(float(dx) - f.x) * lz(float(dy) - f.y);
                        col += texture2D(uTex, sp) * w;
                        wsum += w;
                    }
                }
                gl_FragColor = vec4((col / wsum).rgb, 1.0);
            }
        """
    }
}
