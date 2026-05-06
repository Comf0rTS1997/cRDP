package com.crdp.feature.session

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.crdp.core.rdp.engine.CursorFrame
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Windows 11 style arrow cursor drawn with a Compose [Canvas].
 *
 * The arrow path is laid out in a 15×26 unit grid; the tip (hotspot) sits at
 * (2, 2) so the 2.5-unit black outline isn't clipped by the canvas edges.
 *
 * Use [WindowsCursorHotspot] for the dp offset to subtract from the desired
 * cursor position so the tip lands exactly on the target point.
 */
@Composable
fun WindowsCursor(
    modifier: Modifier = Modifier,
    height: Dp = WindowsCursorHeight,
) {
    val width = height * (WIDTH_UNITS / HEIGHT_UNITS)
    Canvas(modifier = modifier.size(width = width, height = height)) {
        val sx = size.width / WIDTH_UNITS
        val sy = size.height / HEIGHT_UNITS

        val path = Path().apply {
            moveTo(2f * sx, 2f * sy)
            lineTo(2f * sx, 19f * sy)
            lineTo(6f * sx, 15.5f * sy)
            lineTo(8.6f * sx, 22f * sy)
            lineTo(11.1f * sx, 21f * sy)
            lineTo(8.6f * sx, 15f * sy)
            lineTo(13f * sx, 14.6f * sy)
            close()
        }

        // Black outline first; the inner half of the stroke gets covered by the white fill.
        drawPath(
            path = path,
            color = Color.Black,
            style = Stroke(
                width = 2.5f * sx,
                join = StrokeJoin.Round,
                cap = StrokeCap.Round,
            ),
        )
        drawPath(path = path, color = Color.White)
    }
}

/** Default visible height of [WindowsCursor]; 26dp ≈ a Win11 cursor on a touch screen. */
val WindowsCursorHeight: Dp = 26.dp

/** Hotspot offset (tip) within [WindowsCursor] at the default size. */
val WindowsCursorHotspot: Dp = WindowsCursorHeight * (2f / HEIGHT_UNITS)

private const val WIDTH_UNITS = 15f
private const val HEIGHT_UNITS = 26f

/**
 * Renders the active cursor over the RDP surface in trackpad mode.
 *
 * - [CursorFrame.Bitmap]: draw the server-supplied cursor at the bitmap's hotspot.
 * - [CursorFrame.Hidden]: server requested no cursor; draw nothing.
 * - [CursorFrame.Default] / null: fall back to the locally-drawn Windows arrow.
 *
 * (cx, cy) is the cursor position in screen pixels (already transformed from RDP space).
 */
@Composable
fun TrackpadCursor(
    cursor: CursorFrame?,
    cx: Float,
    cy: Float,
    modifier: Modifier = Modifier,
) {
    when (cursor) {
        is CursorFrame.Bitmap -> RemoteCursor(cursor, cx, cy, modifier)
        CursorFrame.Hidden -> Unit
        CursorFrame.Default, null -> LocalDefaultCursor(cx, cy, modifier)
    }
}

@Composable
private fun LocalDefaultCursor(cx: Float, cy: Float, modifier: Modifier) {
    val density = LocalDensity.current
    val hotspotPx = with(density) { WindowsCursorHotspot.toPx() }
    WindowsCursor(
        modifier = modifier.offset {
            IntOffset(
                (cx - hotspotPx).roundToInt(),
                (cy - hotspotPx).roundToInt(),
            )
        },
    )
}

@Composable
private fun RemoteCursor(
    frame: CursorFrame.Bitmap,
    cx: Float,
    cy: Float,
    modifier: Modifier,
) {
    // Build the Android Bitmap once per (size + content) and reuse across recompositions.
    // Using `frame` as the key works because CursorFrame.Bitmap.equals compares pixel content.
    val image = remember(frame) {
        Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).also { bmp ->
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(frame.argb))
        }.asImageBitmap()
    }
    val density = LocalDensity.current
    // Convert bitmap pixels to dp so the cursor scales with display density rather than
    // shrinking on high-DPI screens.
    val widthDp = with(density) { frame.width.toDp() }
    val heightDp = with(density) { frame.height.toDp() }
    val hotXPx = with(density) { frame.hotX.toDp().toPx() }
    val hotYPx = with(density) { frame.hotY.toDp().toPx() }

    Image(
        bitmap = image,
        contentDescription = null,
        filterQuality = FilterQuality.None,
        modifier = modifier
            .offset {
                IntOffset(
                    (cx - hotXPx).roundToInt(),
                    (cy - hotYPx).roundToInt(),
                )
            }
            .size(widthDp, heightDp),
    )
}
