package com.nerfherder.walkthor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

const val LENS_MIN_ZOOM = 1f
const val LENS_MAX_ZOOM = 5f

/**
 * The reading lens: one page shown fit-to-width (zoom 1×), zoomable up to [LENS_MAX_ZOOM]
 * with pinch, double-tap or the − / + buttons, and pannable with one finger.
 *
 * @param naturalSize the page's own size in px; it's scaled so zoom 1× fills the width.
 * @param content draws the page at [naturalSize]; receives the current total scale so
 *                bitmaps can render with matching detail.
 */
@Composable
fun LensView(
    page: Int,
    pageCount: Int,
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    onPageChange: (Int) -> Unit,
    onClose: () -> Unit,
    naturalSize: (Int, Int) -> IntSize,
    modifier: Modifier = Modifier,
    content: @Composable (index: Int, scale: Float) -> Unit,
) {
    val currentZoom by rememberUpdatedState(zoom)
    val zoomChanged by rememberUpdatedState(onZoomChange)

    BoxWithConstraints(modifier.fillMaxSize().background(BookColors.Ink)) {
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        val nat = naturalSize(page, constraints.maxWidth)
        val natW = nat.width.coerceAtLeast(1).toFloat()
        val natH = nat.height.coerceAtLeast(1).toFloat()
        val fit = vw / natW
        val scale = fit * zoom

        var offset by remember { mutableStateOf(Offset.Zero) }
        // Last scale the page was shown at, so button zooms can stay centred.
        val lastScale = remember { FloatArray(1) }

        fun clamp(o: Offset, s: Float): Offset {
            val cw = natW * s
            val ch = natH * s
            val x = if (cw <= vw) (vw - cw) / 2f else o.x.coerceIn(vw - cw, 0f)
            val y = if (ch <= vh) (vh - ch) / 2f else o.y.coerceIn(vh - ch, 0f)
            return Offset(x, y)
        }

        // New page: back to its top, keeping the horizontal position.
        LaunchedEffect(page, natW, natH) { offset = clamp(Offset(offset.x, 0f), scale) }
        // Zoom changed from the buttons: zoom around the centre of the view.
        LaunchedEffect(zoom) {
            val c = Offset(vw / 2f, vh / 2f)
            val prevScale = if (lastScale[0] > 0f) lastScale[0] else scale
            offset = clamp((offset - c) * (scale / prevScale) + c, scale)
            lastScale[0] = scale
        }

        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(page, natW, natH, vw, vh) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val oldScale = fit * currentZoom
                        val newZoom = (currentZoom * gestureZoom).coerceIn(LENS_MIN_ZOOM, LENS_MAX_ZOOM)
                        val newScale = fit * newZoom
                        lastScale[0] = newScale
                        offset = clamp((offset - centroid) * (newScale / oldScale) + centroid + pan, newScale)
                        if (newZoom != currentZoom) zoomChanged(newZoom)
                    }
                }
                .pointerInput(page, natW, natH, vw, vh) {
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            val oldScale = fit * currentZoom
                            val newZoom = if (currentZoom < 1.75f) 2.5f else 1f
                            val newScale = fit * newZoom
                            lastScale[0] = newScale
                            offset = clamp((offset - tap) * (newScale / oldScale) + tap, newScale)
                            zoomChanged(newZoom)
                        },
                    )
                }
        ) {
            val density = LocalDensity.current
            Box(
                Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .requiredSize(with(density) { natW.toDp() }, with(density) { natH.toDp() })
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .background(BookColors.Paper)
            ) {
                content(page, scale)
            }
        }

        // ---- controls ------------------------------------------------------------------
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Close lens", tint = BookColors.Text)
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .background(BookColors.Surface.copy(alpha = 0.94f), RoundedCornerShape(50))
                .border(1.dp, BookColors.Hairline, RoundedCornerShape(50))
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onPageChange(page - 1) }, enabled = page > 0) {
                Icon(AppIcons.ChevronLeft, contentDescription = "Previous page", tint = BookColors.Text)
            }
            Text(
                "${page + 1} / $pageCount",
                color = BookColors.Text,
                fontSize = 13.sp,
                modifier = Modifier.width(76.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = { onPageChange(page + 1) }, enabled = page < pageCount - 1) {
                Icon(AppIcons.ChevronRight, contentDescription = "Next page", tint = BookColors.Text)
            }
            Box(Modifier.width(1.dp).height(22.dp).background(BookColors.Hairline))
            IconButton(onClick = { onZoomChange((zoom / 1.25f).coerceAtLeast(LENS_MIN_ZOOM)) }, enabled = zoom > LENS_MIN_ZOOM) {
                Icon(AppIcons.ZoomOut, contentDescription = "Zoom out", tint = BookColors.Text)
            }
            Text(
                "${(zoom * 100).roundToInt()}%",
                color = BookColors.Brass,
                fontSize = 13.sp,
                modifier = Modifier.width(48.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = { onZoomChange((zoom * 1.25f).coerceAtMost(LENS_MAX_ZOOM)) }, enabled = zoom < LENS_MAX_ZOOM) {
                Icon(AppIcons.ZoomIn, contentDescription = "Zoom in", tint = BookColors.Text)
            }
        }
    }
}
