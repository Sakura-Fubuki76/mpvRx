package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class SeekPreviewPositionProviderTest {
  @Test fun measuredPreviewStaysAboveTrackInWindowCoordinates() {
    val provider = SeekPreviewPositionProvider(24, 0.5f)
    assertEquals(IntOffset(360, 1040), provider.calculatePosition(
      IntRect(60, 1400, 1140, 1448), IntSize(1200, 2608), LayoutDirection.Ltr, IntSize(480, 360)))
  }

  @Test fun previewClampsAtBothEdgesAndSupportsRtl() {
    val bounds = IntRect(60, 400, 1140, 448)
    val window = IntSize(1200, 800)
    val preview = IntSize(480, 300)
    assertEquals(0, SeekPreviewPositionProvider(24, 0f).calculatePosition(bounds, window, LayoutDirection.Ltr, preview).x)
    assertEquals(720, SeekPreviewPositionProvider(24, 1f).calculatePosition(bounds, window, LayoutDirection.Ltr, preview).x)
    assertEquals(720, SeekPreviewPositionProvider(24, 0f).calculatePosition(bounds, window, LayoutDirection.Rtl, preview).x)
    assertEquals(0, SeekPreviewPositionProvider(24, 0.5f).calculatePosition(
      IntRect(60, 100, 1140, 148), window, LayoutDirection.Ltr, preview).y)
  }
}
