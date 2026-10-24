package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekPreviewPlacementTest {
  @Test
  fun `vertical sheet keeps tall aspect`() {
    val (width, height) = SeekPreviewPlacement.cellSizeDp(270, 480)

    assertEquals(90f, width, 0.01f)
    assertEquals(160f, height, 0.01f)
  }

  @Test
  fun `horizontal sheet keeps wide aspect`() {
    val (width, height) = SeekPreviewPlacement.cellSizeDp(480, 270)

    assertEquals(160f, width, 0.01f)
    assertEquals(90f, height, 0.01f)
  }

  @Test
  fun `missing metadata falls back to sixteen by nine`() {
    val (width, height) = SeekPreviewPlacement.cellSizeDp(0, 0)

    assertEquals(SeekPreviewPlacement.FALLBACK_WIDTH_DP, width, 0.01f)
    assertEquals(SeekPreviewPlacement.FALLBACK_HEIGHT_DP, height, 0.01f)
  }

  @Test
  fun `offset centers preview on the thumb`() {
    assertEquals(900, SeekPreviewPlacement.offsetX(anchorWidthPx = 2000, fraction = 0.5f, previewWidthPx = 200))
  }

  @Test
  fun `offset clamps at both ends`() {
    assertEquals(0, SeekPreviewPlacement.offsetX(anchorWidthPx = 2000, fraction = 0f, previewWidthPx = 200))
    assertEquals(1800, SeekPreviewPlacement.offsetX(anchorWidthPx = 2000, fraction = 1f, previewWidthPx = 200))
  }

  @Test
  fun `offset never goes negative when preview is wider than the bar`() {
    val offset = SeekPreviewPlacement.offsetX(anchorWidthPx = 120, fraction = 0.5f, previewWidthPx = 400)

    assertTrue(offset >= 0)
    assertEquals(0, offset)
  }

  @Test
  fun `origin sits just above the measured bar`() {
    val origin =
      SeekPreviewPlacement.origin(
        anchorPosition = IntOffset(68, 2130),
        anchorWidthPx = 1064,
        fraction = 0.5f,
        previewWidthPx = 257,
        previewHeightPx = 456,
        gapPx = 48,
      )

    assertEquals(472, origin.x)
    assertEquals(1626, origin.y)
  }

  @Test
  fun `origin never leaves the top of the window`() {
    val origin =
      SeekPreviewPlacement.origin(
        anchorPosition = IntOffset(68, 200),
        anchorWidthPx = 1064,
        fraction = 0.5f,
        previewWidthPx = 257,
        previewHeightPx = 456,
        gapPx = 48,
      )

    assertEquals(0, origin.y)
  }
}
