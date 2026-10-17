package app.gyrolet.mpvrx.domain.thumbnail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The blank-frame heuristic decides whether a decoded thumbnail is a flat card (black lead-in,
 * fade, static colour screen) or a picture. These cases pin the behaviour that made dim WebDAV
 * footage lose its thumbnail: darkness alone must not read as blank, while genuinely flat frames
 * still do.
 */
class FrameFlatnessTest {
  private val width = 40
  private val height = 24

  private fun argb(
    red: Int,
    green: Int,
    blue: Int,
  ): Int = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

  private fun frame(rule: (x: Int, y: Int) -> Int): IntArray =
    IntArray(width * height) { index -> rule(index % width, index / width) }

  private fun blank(pixels: IntArray): Boolean =
    measureFrameFlatness(pixels, width, height).isBlank

  @Test
  fun solidBlackFrameIsBlank() {
    assertTrue(blank(frame { _, _ -> argb(0, 0, 0) }))
  }

  @Test
  fun blackFrameWithCodecNoiseIsStillBlank() {
    val random = Random(7)
    val pixels = frame { _, _ -> val value = random.nextInt(0, 4); argb(value, value, value) }
    assertTrue(blank(pixels))
  }

  @Test
  fun staticColourCardIsBlank() {
    assertTrue(blank(frame { _, _ -> argb(0x33, 0x66, 0xCC) }))
  }

  @Test
  fun fullyTransparentPreviewIsBlank() {
    assertTrue(blank(IntArray(width * height)))
  }

  @Test
  fun darkSceneWithASubjectIsNotBlank() {
    val pixels =
      frame { x, y ->
        val background = 12 + x * 4 / width + y * 2 / height
        val value = if (x in 12..27 && y in 6..17) 80 else background
        argb(value, value, value)
      }
    assertFalse(blank(pixels))
  }

  @Test
  fun dimLowLightTextureIsNotBlank() {
    // Every pixel is dark and within tolerance of the average, but grain gives the frame edges.
    val random = Random(11)
    val pixels =
      frame { _, _ ->
        val value = 20 + random.nextInt(-10, 11)
        argb(value, value, value)
      }
    assertFalse(blank(pixels))
  }

  @Test
  fun twoToneCardWithEqualLuminanceIsNotBlank() {
    // Red and blue differ in colour but not much in luminance; the colour edge must still count.
    val pixels = frame { x, _ -> if (x < width / 2) argb(0xE0, 0x20, 0x20) else argb(0x20, 0x20, 0xE0) }
    assertFalse(blank(pixels))
  }

  @Test
  fun colourfulSceneIsNotBlank() {
    val pixels = frame { x, y -> argb(x * 6 % 256, y * 10 % 256, (x + y) * 5 % 256) }
    assertFalse(blank(pixels))
  }

  @Test
  fun tinyFrameIsNotRejected() {
    assertFalse(measureFrameFlatness(intArrayOf(argb(0, 0, 0)), 1, 1).isBlank)
    assertFalse(measureFrameFlatness(IntArray(3) { argb(0, 0, 0) }, 3, 1).isBlank)
  }
}
