package com.crdp.core.rdp.engine

/**
 * Selects how the RDP framebuffer is composited to the destination [android.view.Surface].
 *
 * - [Auto]: engine picks the best available backend at attach time. Today this means
 *   prefer GLES on API ≥ 26 with a working EGL context, otherwise fall back to HWUI Canvas.
 * - [HwuiCanvas]: classic `Surface.lockHardwareCanvas` + `Canvas.drawBitmap` path. The
 *   safest baseline; uses HWUI's bilinear sampler.
 * - [Gles]: dedicated EGL14 + GLES2 context bound to the destination Surface. Supports
 *   user-selectable sampling ([SamplingMode]) and bypasses HWUI's per-frame texture
 *   re-recording. See `engine/afreerdp` GlSurfaceRenderer.
 */
enum class RenderBackend { Auto, HwuiCanvas, Gles }

/**
 * Texel sampling filter used by the GLES renderer. Ignored by the HWUI Canvas backend.
 *
 * - [Nearest]: pixel-perfect at integer zoom. Best for legible Windows text where HWUI's
 *   bilinear blur is undesirable.
 * - [Bilinear]: smooth scaling for fractional zoom. Default.
 * - [Lanczos3]: shader-based 3-tap Lanczos. Sharper than bilinear at the cost of a few
 *   extra ALU ops per pixel; opt-in.
 */
enum class SamplingMode { Nearest, Bilinear, Lanczos3 }

/** Bundle of render-side options applied at [RdpEngine.attachSurface]. */
data class RenderOptions(
    val backend: RenderBackend = RenderBackend.Auto,
    val sampling: SamplingMode = SamplingMode.Bilinear,
)
