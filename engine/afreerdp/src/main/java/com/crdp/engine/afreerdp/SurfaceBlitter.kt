package com.crdp.engine.afreerdp

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.view.Surface
import com.crdp.core.rdp.engine.RenderBackend
import com.crdp.core.rdp.engine.RenderOptions
import com.crdp.core.rdp.engine.SamplingMode
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * Front for the active render strategy. The engine talks to this; this picks
 * between an HWUI [Surface.lockHardwareCanvas] path (the existing baseline) and
 * the GLES [GlSurfaceRenderer] path (perf-improve.md Tier 3) based on the
 * [RenderBackend] handed in at [attach] time.
 *
 * `Auto` resolves to HWUI today; the GLES path is opt-in until it's been
 * validated on more devices. Switching the default is a one-line change once
 * we're confident.
 *
 * Hot-path properties (HWUI path):
 *  - [currentBitmap] is a volatile read; no lock per JNI updateGraphics call.
 *  - [flushDirty] takes the surface/bitmap lock once per blit, never per JNI call.
 *  - Letterbox transform is cached; recomputed only on size change.
 *  - Frame-pacing uses [System.nanoTime] against a refresh-rate-aware interval.
 *  - Skips the per-frame `drawColor(BLACK)` clear when the bitmap fully covers
 *    the surface.
 *  - Calls [Bitmap.prepareToDraw] before each blit to nudge HWUI's texture cache.
 *
 * Hot-path properties (GLES path):
 *  - All EGL / GL state lives in [GlSurfaceRenderer] on its dedicated render
 *    thread; no per-flush thread hops on the engine worker.
 *  - Whole-bitmap upload via `GLUtils.texSubImage2D` (one native copy).
 *  - User-selectable sampling (nearest / bilinear / lanczos3).
 */
internal class SurfaceBlitter {

    private val lock = ReentrantLock()
    @Volatile private var bitmap: Bitmap? = null
    private var surface: Surface? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    @Volatile private var backend: RenderBackend = RenderBackend.HwuiCanvas
    @Volatile private var sampling: SamplingMode = SamplingMode.Bilinear
    private var glRenderer: GlSurfaceRenderer? = null

    // ── HWUI-path state ────────────────────────────────────────────────────────
    private val cachedDst = RectF()
    private var coversFullSurface = false
    private var lastBmpW = -1
    private var lastBmpH = -1
    private var lastSurW = -1
    private var lastSurH = -1
    private var minFrameIntervalNanos: Long = DEFAULT_FRAME_INTERVAL_NANOS
    private var lastFlushNanos: Long = 0L

    // Dirty region — single producer (engine worker), HWUI-path only.
    private var dirtyLeft = Int.MAX_VALUE
    private var dirtyTop = Int.MAX_VALUE
    private var dirtyRight = 0
    private var dirtyBottom = 0

    fun attach(
        surface: Surface,
        w: Int,
        h: Int,
        refreshHz: Float = 60f,
        options: RenderOptions = RenderOptions(),
    ) {
        val needFullRepaint: Boolean
        lock.withLock {
            this.surface = surface
            surfaceWidth = w
            surfaceHeight = h
            minFrameIntervalNanos = if (refreshHz > 0f) (1_000_000_000.0 / refreshHz).toLong()
                                    else DEFAULT_FRAME_INTERVAL_NANOS
            lastFlushNanos = 0L
            invalidateTransform()

            val resolved = when (options.backend) {
                RenderBackend.Auto -> RenderBackend.HwuiCanvas
                RenderBackend.HwuiCanvas -> RenderBackend.HwuiCanvas
                RenderBackend.Gles -> RenderBackend.Gles
            }
            sampling = options.sampling

            // If we're switching backends, tear down the previous renderer and bring
            // up the new one. Bitmap is shared between backends; don't recycle here.
            if (resolved != backend) {
                val prevGl = glRenderer
                glRenderer = null
                prevGl?.release()
            }
            backend = resolved

            if (resolved == RenderBackend.Gles) {
                val gl = glRenderer ?: GlSurfaceRenderer().also { glRenderer = it }
                bitmap?.let(gl::adoptBitmap)
                gl.attach(surface, w, h, sampling, refreshHz)
            } else {
                glRenderer?.setSampling(sampling)
            }

            // Surface rotation / DeX resize hands us a fresh Surface that is fully
            // black until the next FreeRDP graphics update lands. Mark the whole
            // existing bitmap dirty so the next flushDirty paints it end-to-end —
            // otherwise half the surface stays black until the user causes a
            // remote-side redraw.
            val bmp = bitmap
            if (bmp != null && backend == RenderBackend.HwuiCanvas) {
                dirtyLeft = 0
                dirtyTop = 0
                dirtyRight = bmp.width
                dirtyBottom = bmp.height
                needFullRepaint = true
            } else {
                needFullRepaint = false
            }
        }
        // Flush outside the lock; flushHwui acquires it briefly itself and the
        // actual canvas draw must run unlocked (lockHardwareCanvas blocks).
        if (needFullRepaint) {
            flushDirty()
        }
    }

    fun detach() = lock.withLock {
        surface = null
        glRenderer?.detach()
    }

    fun release() = lock.withLock {
        surface = null
        glRenderer?.release()
        glRenderer = null
        bitmap?.recycle()
        bitmap = null
        surfaceWidth = 0
        surfaceHeight = 0
        invalidateTransform()
    }

    /** Bitmap handed to FreeRDP for painting. Sized to the RDP session resolution. */
    fun bitmapFor(width: Int, height: Int): Bitmap = lock.withLock {
        var bmp = bitmap
        if (bmp == null || bmp.width != width || bmp.height != height) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap = bmp
            invalidateTransform()
        }
        // Mirror into GL renderer (it owns its own reference to the same Bitmap object).
        glRenderer?.let { gl ->
            // The GL renderer's bitmapFor allocates its own; we share by handing
            // the same Bitmap instance via a tracked reference. To avoid the GL
            // renderer recycling out from under us, set its current bitmap directly.
            gl.adoptBitmap(bmp!!)
        }
        bmp!!
    }

    /** Lock-free read; the worker thread hits this once per JNI dirty-rect update. */
    fun currentBitmap(): Bitmap? = bitmap

    /** Expand the pending dirty region. Worker thread only. */
    fun markDirty(x: Int, y: Int, w: Int, h: Int) {
        when (backend) {
            RenderBackend.Gles -> glRenderer?.markDirty(x, y, w, h)
            else -> {
                if (dirtyLeft > x) dirtyLeft = x
                if (dirtyTop > y) dirtyTop = y
                val r = x + w; if (dirtyRight < r) dirtyRight = r
                val b = y + h; if (dirtyBottom < b) dirtyBottom = b
            }
        }
    }

    /**
     * Blit if a frame is due. Returns the union dirty rect in bitmap coords if
     * a flush happened, or `null` if throttled / nothing to do.
     */
    fun flushDirty(): IntArray? {
        return when (backend) {
            RenderBackend.Gles -> glRenderer?.flushDirty()
            else -> flushHwui()
        }
    }

    private fun flushHwui(): IntArray? {
        if (dirtyRight <= dirtyLeft || dirtyBottom <= dirtyTop) return null
        val now = System.nanoTime()
        if (now - lastFlushNanos < minFrameIntervalNanos) return null

        val dx = dirtyLeft; val dy = dirtyTop
        val dw = dirtyRight - dx; val dh = dirtyBottom - dy
        dirtyLeft = Int.MAX_VALUE; dirtyTop = Int.MAX_VALUE
        dirtyRight = 0; dirtyBottom = 0

        val s: Surface
        val src: Bitmap
        val needsClear: Boolean
        lock.withLock {
            s = surface ?: return null
            src = bitmap ?: return null
            ensureTransform(src.width, src.height)
            needsClear = !coversFullSurface
        }

        if (!s.isValid) return null

        val canvas = try {
            s.lockHardwareCanvas() ?: s.lockCanvas(null)
        } catch (_: IllegalArgumentException) { null }
          catch (_: IllegalStateException) { null }
        if (canvas == null) return null

        try {
            if (needsClear) canvas.drawColor(Color.BLACK)
            src.prepareToDraw()
            canvas.drawBitmap(src, null, cachedDst, null)
        } finally {
            try { s.unlockCanvasAndPost(canvas) } catch (_: IllegalStateException) { }
        }

        lastFlushNanos = now
        return intArrayOf(dx, dy, dw, dh)
    }

    private fun invalidateTransform() {
        lastBmpW = -1; lastBmpH = -1; lastSurW = -1; lastSurH = -1
    }

    private fun ensureTransform(bmpW: Int, bmpH: Int) {
        if (bmpW == lastBmpW && bmpH == lastBmpH
                && surfaceWidth == lastSurW && surfaceHeight == lastSurH) return
        lastBmpW = bmpW; lastBmpH = bmpH
        lastSurW = surfaceWidth; lastSurH = surfaceHeight

        if (surfaceWidth <= 0 || surfaceHeight <= 0 || bmpW <= 0 || bmpH <= 0) {
            cachedDst.set(0f, 0f, 0f, 0f)
            coversFullSurface = false
            return
        }
        val scale = min(surfaceWidth.toFloat() / bmpW, surfaceHeight.toFloat() / bmpH)
        val outW = bmpW * scale
        val outH = bmpH * scale
        val offX = (surfaceWidth - outW) / 2f
        val offY = (surfaceHeight - outH) / 2f
        cachedDst.set(offX, offY, offX + outW, offY + outH)
        coversFullSurface = offX < 0.5f && offY < 0.5f &&
                outW + 0.5f >= surfaceWidth && outH + 0.5f >= surfaceHeight
    }

    private companion object {
        const val DEFAULT_FRAME_INTERVAL_NANOS = 16_666_667L  // 60Hz
    }
}
