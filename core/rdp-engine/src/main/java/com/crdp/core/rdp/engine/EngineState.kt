package com.crdp.core.rdp.engine

sealed interface EngineState {
    data object Idle : EngineState
    data object Connecting : EngineState
    data class Connected(
        val detail: String,
        val bytesSent: Long = 0L,
        val bytesReceived: Long = 0L,
    ) : EngineState
    data object Disconnecting : EngineState
    /**
     * Session was once Connected and then dropped without a local disconnect() call —
     * server kick (other client signed in), network loss, remote logoff, etc.
     * Distinguished from [Idle] (clean teardown) so the UI can show a retry prompt.
     */
    data class Disconnected(val reason: String) : EngineState
    data class Error(val message: String) : EngineState
}

data class FrameUpdate(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * Cursor delivered by the engine.
 *
 * - [Bitmap]: server-supplied cursor with pixels in ARGB_8888 byte order —
 *   bytes in memory are R,G,B,A (length = width * height * 4). [hotX]/[hotY]
 *   are the hotspot in pixels.
 * - [Default]: engine has no bitmap to render; UI should fall back to a local cursor.
 * - [Hidden]: server requested the cursor be invisible.
 */
sealed interface CursorFrame {
    data class Bitmap(
        val argb: ByteArray,
        val width: Int,
        val height: Int,
        val hotX: Int,
        val hotY: Int,
    ) : CursorFrame {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bitmap) return false
            return width == other.width && height == other.height &&
                hotX == other.hotX && hotY == other.hotY && argb.contentEquals(other.argb)
        }
        override fun hashCode(): Int {
            var r = width
            r = 31 * r + height
            r = 31 * r + hotX
            r = 31 * r + hotY
            r = 31 * r + argb.contentHashCode()
            return r
        }
    }
    data object Default : CursorFrame
    data object Hidden : CursorFrame
}
