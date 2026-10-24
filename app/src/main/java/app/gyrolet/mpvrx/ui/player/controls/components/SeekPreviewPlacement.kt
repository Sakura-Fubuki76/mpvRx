package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.roundToInt

/**
 * Geometry for the scrub preview, kept as pure functions so the placement is testable without a
 * Compose host.
 *
 * The preview is a child of the seekbar and is laid out with an offset, so all it needs is the
 * cell's display size and the horizontal offset that keeps it centred on the thumb.
 */
internal object SeekPreviewPlacement {
  /** Longest cell edge in dp; every sprite cell is scaled to fit this box. */
  const val MAX_EDGE_DP = 160f

  /** Fallback cell aspect used until the sprite sheet for the current item is ready. */
  const val FALLBACK_WIDTH_DP = 160f
  const val FALLBACK_HEIGHT_DP = 90f

  /**
   * Cell size in dp, preserving the sprite sheet's aspect ratio. Vertical videos produce tall
   * cells (for example 90x160), horizontal ones wide cells (160x90).
   */
  fun cellSizeDp(
    cellWidth: Int,
    cellHeight: Int,
  ): Pair<Float, Float> {
    val longest = maxOf(cellWidth, cellHeight)
    if (cellWidth <= 0 || cellHeight <= 0 || longest <= 0) {
      return FALLBACK_WIDTH_DP to FALLBACK_HEIGHT_DP
    }
    val scale = MAX_EDGE_DP / longest
    return cellWidth * scale to cellHeight * scale
  }

  /**
   * Horizontal offset of the preview's left edge inside the seekbar, so the preview stays centred
   * on the thumb and never leaves the bar.
   */
  fun offsetX(
    anchorWidthPx: Int,
    fraction: Float,
    previewWidthPx: Int,
  ): Int {
    if (anchorWidthPx <= 0 || previewWidthPx <= 0) return 0
    val progress = fraction.coerceIn(0f, 1f)
    val centered = anchorWidthPx * progress - previewWidthPx / 2f
    val maxOffset = (anchorWidthPx - previewWidthPx).coerceAtLeast(0)
    return centered.roundToInt().coerceIn(0, maxOffset)
  }

  /**
   * Absolute position of the preview window: centred on the thumb, just above the bar.
   *
   * [anchorPosition] is the seekbar's own position in window coordinates, measured with
   * `positionInWindow`. Compose's own anchor rectangle is not usable here: the popup is created on
   * the frame scrubbing starts, and the rect it hands to the provider then does not match where the
   * bar ends up, which stranded the sprite in the screen corner.
   */
  fun origin(
    anchorPosition: IntOffset,
    anchorWidthPx: Int,
    fraction: Float,
    previewWidthPx: Int,
    previewHeightPx: Int,
    gapPx: Int,
  ): IntOffset =
    IntOffset(
      anchorPosition.x + offsetX(anchorWidthPx, fraction, previewWidthPx),
      (anchorPosition.y - previewHeightPx - gapPx).coerceAtLeast(0),
    )
}

/** Reports a position measured by the caller instead of Compose's anchor rectangle. */
internal class FixedPreviewPositionProvider(
  private val position: IntOffset,
) : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset = position
}
