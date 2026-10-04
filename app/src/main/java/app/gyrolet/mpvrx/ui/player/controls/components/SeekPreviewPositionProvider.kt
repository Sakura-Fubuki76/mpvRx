package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt

/** Uses window coordinates and the measured preview, independent of sprite pixel resolution. */
internal class SeekPreviewPositionProvider(
  private val gapPx: Int,
  private val fraction: Float,
) : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val progress = fraction.coerceIn(0f, 1f).let { if (layoutDirection == LayoutDirection.Rtl) 1f - it else it }
    val x = (anchorBounds.left + anchorBounds.width * progress - popupContentSize.width / 2f).roundToInt()
    val y = anchorBounds.top + anchorBounds.height / 2 - popupContentSize.height - gapPx
    return IntOffset(x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
      y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
  }
}
